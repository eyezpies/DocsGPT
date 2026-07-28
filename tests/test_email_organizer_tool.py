from datetime import datetime, timedelta
from email.message import Message
from unittest.mock import patch

import pytest

from application.agents.tools.email_organizer import EmailOrganizerTool


def _raw_header(uid, sender, subject, date):
    msg = Message()
    msg["From"] = sender
    msg["Subject"] = subject
    msg["Date"] = date.strftime("%a, %d %b %Y %H:%M:%S +0000")
    return msg.as_bytes()


class FakeIMAP:
    """Minimal stand-in for imaplib.IMAP4_SSL used by EmailOrganizerTool."""

    def __init__(self, messages, folders=None):
        # messages: list of dicts {uid, from, subject, date, seen}
        self.messages = {str(m["uid"]): m for m in messages}
        self.folders = folders or ["INBOX", "Archive", "Trash"]
        self.calls = []
        self.logged_out = False

    def login(self, user, password):
        self.calls.append(("login", user, password))

    def select(self, folder, readonly=False):
        self.calls.append(("select", folder))
        return "OK", [b"1"]

    def list(self):
        raw = [f'(\\HasNoChildren) "/" "{f}"'.encode() for f in self.folders]
        return "OK", raw

    def uid(self, command, *args):
        self.calls.append(("uid", command, *args))
        if command == "search":
            uids = " ".join(self.messages.keys()).encode()
            return "OK", [uids]
        if command == "fetch":
            uid = args[0]
            uid = uid.decode() if isinstance(uid, bytes) else uid
            message = self.messages.get(uid)
            if message is None:
                return "OK", [None]
            flag = "\\Seen" if not message.get("unread") else ""
            metadata = f"1 (UID {uid} FLAGS ({flag}))".encode()
            header = _raw_header(
                uid, message["from"], message["subject"], message["date"]
            )
            return "OK", [(metadata, header)]
        if command in ("copy", "store"):
            return "OK", [b"Completed"]
        raise ValueError(f"Unexpected uid command {command}")

    def expunge(self):
        self.calls.append(("expunge",))

    def logout(self):
        self.logged_out = True


@pytest.fixture
def base_config():
    return {
        "imap_server": "imap.example.com",
        "imap_port": "993",
        "email": "user@example.com",
        "password": "secret",
    }


def test_missing_config_raises(base_config):
    tool = EmailOrganizerTool({})
    with pytest.raises(ValueError, match="not configured"):
        tool.execute_action("email_list_folders")


def test_list_folders(base_config):
    fake_imap = FakeIMAP(messages=[], folders=["INBOX", "Archive"])
    tool = EmailOrganizerTool(base_config)
    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_list_folders")

    assert result["status"] == "success"
    assert "INBOX" in result["folders"]
    assert "Archive" in result["folders"]
    assert fake_imap.logged_out is True


def test_organize_dry_run_does_not_move(base_config):
    now = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "newsletter@ads.com", "subject": "Sale!", "date": now},
        {"uid": 2, "from": "boss@work.com", "subject": "Report", "date": now},
    ]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)
    rules = [{"sender_contains": "ads.com", "target_folder": "Promotions"}]

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_organize", rules=rules, dry_run=True)

    assert result["dry_run"] is True
    assert result["moved_by_folder"]["Promotions"] == 1
    assert not any(call[1] == "copy" for call in fake_imap.calls if call[0] == "uid")


def test_organize_moves_matching_messages(base_config):
    now = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "newsletter@ads.com", "subject": "Sale!", "date": now},
        {"uid": 2, "from": "boss@work.com", "subject": "Report", "date": now},
    ]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)
    rules = [{"sender_contains": "ads.com", "target_folder": "Promotions"}]

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_organize", rules=rules, dry_run=False)

    assert result["moved_by_folder"]["Promotions"] == 1
    copy_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "copy"]
    assert len(copy_calls) == 1
    assert copy_calls[0][3] == "Promotions"
    assert ("expunge",) in fake_imap.calls


def test_organize_requires_target_folder(base_config):
    tool = EmailOrganizerTool(base_config)
    with pytest.raises(ValueError, match="target_folder"):
        tool.execute_action(
            "email_organize", rules=[{"sender_contains": "x"}], dry_run=True
        )


def test_organize_requires_a_condition(base_config):
    tool = EmailOrganizerTool(base_config)
    with pytest.raises(ValueError, match="at least one"):
        tool.execute_action(
            "email_organize", rules=[{"target_folder": "Archive"}], dry_run=True
        )


def test_purge_requires_filter(base_config):
    tool = EmailOrganizerTool(base_config)
    with pytest.raises(ValueError, match="At least one filter"):
        tool.execute_action("email_purge")


def test_purge_dry_run_is_default(base_config):
    old_date = datetime.utcnow() - timedelta(days=400)
    messages = [{"uid": 1, "from": "spam@ads.com", "subject": "Old", "date": old_date}]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_purge", older_than_days=365)

    assert result["dry_run"] is True
    assert result["matched"] == 1
    assert not any(c[0] == "uid" and c[1] == "store" for c in fake_imap.calls)


def test_purge_moves_to_trash_by_default(base_config):
    old_date = datetime.utcnow() - timedelta(days=400)
    messages = [{"uid": 1, "from": "spam@ads.com", "subject": "Old", "date": old_date}]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_purge", older_than_days=365, dry_run=False)

    assert result["matched"] == 1
    copy_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "copy"]
    assert len(copy_calls) == 1
    assert copy_calls[0][3] == "Trash"
    assert ("expunge",) in fake_imap.calls


def test_purge_permanent_skips_trash_copy(base_config):
    old_date = datetime.utcnow() - timedelta(days=400)
    messages = [{"uid": 1, "from": "spam@ads.com", "subject": "Old", "date": old_date}]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action(
            "email_purge", older_than_days=365, dry_run=False, permanent=True
        )

    assert result["matched"] == 1
    assert not any(c[0] == "uid" and c[1] == "copy" for c in fake_imap.calls)
    store_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "store"]
    assert len(store_calls) == 1


def test_purge_unread_only_filter(base_config):
    now = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "a@x.com", "subject": "Read", "date": now, "unread": False},
        {"uid": 2, "from": "b@x.com", "subject": "Unread", "date": now, "unread": True},
    ]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_purge", unread_only=True)

    assert result["matched"] == 1


def test_organize_importance_flags_message_without_moving(base_config):
    now = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "boss@work.com", "subject": "Urgent", "date": now},
        {"uid": 2, "from": "newsletter@ads.com", "subject": "Sale!", "date": now},
    ]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)
    rules = [{"sender_contains": "boss@work.com", "importance": "important"}]

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_organize", rules=rules, dry_run=False)

    assert result["tagged"] == 1
    assert result["moved_by_folder"] == {}
    store_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "store"]
    assert len(store_calls) == 1
    assert store_calls[0][3] == "+FLAGS"
    assert "\\Flagged" in store_calls[0][4]
    assert not any(c[0] == "uid" and c[1] == "copy" for c in fake_imap.calls)


def test_organize_normal_importance_removes_flag(base_config):
    now = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "newsletter@ads.com", "subject": "Sale!", "date": now}
    ]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)
    rules = [{"sender_contains": "ads.com", "importance": "normal"}]

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_organize", rules=rules, dry_run=False)

    assert result["tagged"] == 1
    store_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "store"]
    assert len(store_calls) == 1
    assert store_calls[0][3] == "-FLAGS"
    assert "\\Flagged" in store_calls[0][4]


def test_organize_can_both_move_and_tag(base_config):
    now = datetime.utcnow()
    messages = [{"uid": 1, "from": "boss@work.com", "subject": "Urgent", "date": now}]
    fake_imap = FakeIMAP(messages)
    tool = EmailOrganizerTool(base_config)
    rules = [
        {
            "sender_contains": "boss@work.com",
            "target_folder": "Priority",
            "importance": "important",
        }
    ]

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_organize", rules=rules, dry_run=False)

    assert result["moved_by_folder"]["Priority"] == 1
    assert result["tagged"] == 1


def test_delete_junk_requires_no_filter_and_defaults_to_dry_run(base_config):
    messages = [
        {
            "uid": 1,
            "from": "spam@ads.com",
            "subject": "Buy now",
            "date": datetime.utcnow(),
        }
    ]
    fake_imap = FakeIMAP(messages, folders=["INBOX", "Junk", "Trash"])
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_delete_junk")

    assert result["dry_run"] is True
    assert result["folder"] == "Junk"
    assert result["matched"] == 1
    assert not any(c[0] == "uid" and c[1] == "store" for c in fake_imap.calls)


def test_delete_junk_moves_to_trash_by_default(base_config):
    messages = [
        {
            "uid": 1,
            "from": "spam@ads.com",
            "subject": "Buy now",
            "date": datetime.utcnow(),
        }
    ]
    fake_imap = FakeIMAP(messages, folders=["INBOX", "Junk", "Trash"])
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_delete_junk", dry_run=False)

    assert result["matched"] == 1
    copy_calls = [c for c in fake_imap.calls if c[0] == "uid" and c[1] == "copy"]
    assert len(copy_calls) == 1
    assert copy_calls[0][3] == "Trash"
    assert ("expunge",) in fake_imap.calls


def test_delete_junk_permanent(base_config):
    messages = [
        {
            "uid": 1,
            "from": "spam@ads.com",
            "subject": "Buy now",
            "date": datetime.utcnow(),
        }
    ]
    fake_imap = FakeIMAP(messages, folders=["INBOX", "Junk", "Trash"])
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_delete_junk", dry_run=False, permanent=True)

    assert result["matched"] == 1
    assert not any(c[0] == "uid" and c[1] == "copy" for c in fake_imap.calls)


def test_delete_junk_respects_older_than_days(base_config):
    old_date = datetime.utcnow() - timedelta(days=30)
    recent_date = datetime.utcnow()
    messages = [
        {"uid": 1, "from": "spam@ads.com", "subject": "Old junk", "date": old_date},
        {"uid": 2, "from": "spam@ads.com", "subject": "New junk", "date": recent_date},
    ]
    fake_imap = FakeIMAP(messages, folders=["INBOX", "Junk", "Trash"])
    tool = EmailOrganizerTool(base_config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_delete_junk", older_than_days=7)

    assert result["scanned"] == 2
    assert result["matched"] == 1


def test_delete_junk_uses_configured_folder():
    config = {
        "imap_server": "imap.example.com",
        "email": "user@example.com",
        "password": "secret",
        "junk_folder": "[Gmail]/Spam",
    }
    fake_imap = FakeIMAP([], folders=["INBOX", "[Gmail]/Spam", "Trash"])
    tool = EmailOrganizerTool(config)

    with patch("imaplib.IMAP4_SSL", return_value=fake_imap):
        result = tool.execute_action("email_delete_junk")

    assert result["folder"] == "[Gmail]/Spam"
    assert ("select", "[Gmail]/Spam") in fake_imap.calls


def test_get_actions_metadata_and_config(base_config):
    tool = EmailOrganizerTool(base_config)
    names = {action["name"] for action in tool.get_actions_metadata()}
    assert names == {
        "email_list_folders",
        "email_organize",
        "email_purge",
        "email_delete_junk",
    }

    config_reqs = tool.get_config_requirements()
    assert config_reqs["imap_server"]["required"] is True
    assert config_reqs["password"]["secret"] is True
    assert "junk_folder" in config_reqs
