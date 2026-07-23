import email
import imaplib
import logging
from datetime import datetime, timedelta

from application.agents.tools.base import Tool

logger = logging.getLogger(__name__)

MAX_MESSAGES = 500


class EmailOrganizerTool(Tool):
    """
    Email Organizer & Purger
    Connects to an email account over IMAP to automatically sort messages
    into folders based on rules, and to purge (archive/delete) messages
    that match a set of filters (age, sender, subject, read status).
    """

    def __init__(self, config):
        self.config = config
        self.imap_server = config.get("imap_server", "")
        self.imap_port = int(config.get("imap_port", 993) or 993)
        self.email_address = config.get("email", "")
        self.password = config.get("password", "")
        self.use_ssl = config.get("use_ssl", True) in (True, "true", "True", "1", 1)
        self.trash_folder = config.get("trash_folder", "Trash")

    def execute_action(self, action_name, **kwargs):
        actions = {
            "email_list_folders": self._list_folders,
            "email_organize": self._organize,
            "email_purge": self._purge,
        }
        if action_name not in actions:
            raise ValueError(f"Unknown action: {action_name}")
        return actions[action_name](**kwargs)

    def _connect(self):
        if not self.imap_server or not self.email_address or not self.password:
            raise ValueError(
                "Email account is not configured (imap_server, email, password required)"
            )
        imap_cls = imaplib.IMAP4_SSL if self.use_ssl else imaplib.IMAP4
        imap = imap_cls(self.imap_server, self.imap_port)
        imap.login(self.email_address, self.password)
        return imap

    def _list_folders(self):
        imap = self._connect()
        try:
            status, raw_folders = imap.list()
            if status != "OK":
                return {"status": "error", "message": "Failed to list folders"}
            folders = []
            for raw in raw_folders:
                decoded = raw.decode(errors="ignore") if isinstance(raw, bytes) else raw
                name = decoded.split('"/"')[-1].strip().strip('"')
                folders.append(name)
            return {"status": "success", "folders": folders}
        finally:
            imap.logout()

    def _fetch_headers(self, imap, folder, limit):
        status, _ = imap.select(folder, readonly=False)
        if status != "OK":
            raise ValueError(f"Could not open folder '{folder}'")

        status, data = imap.uid("search", None, "ALL")
        if status != "OK":
            raise ValueError(f"Search failed on folder '{folder}'")

        uids = data[0].split()
        uids = uids[-min(limit, MAX_MESSAGES) :]

        messages = []
        for uid in uids:
            status, msg_data = imap.uid(
                "fetch", uid, "(FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])"
            )
            if status != "OK" or not msg_data or msg_data[0] is None:
                continue

            metadata = msg_data[0][0]
            metadata_text = (
                metadata.decode(errors="ignore")
                if isinstance(metadata, bytes)
                else str(metadata)
            )
            is_unread = "\\Seen" not in metadata_text

            raw_header = msg_data[0][1]
            parsed = email.message_from_bytes(raw_header)
            sent_date = self._parse_date(parsed.get("Date"))

            messages.append(
                {
                    "uid": uid,
                    "from": (parsed.get("From") or "").lower(),
                    "subject": (parsed.get("Subject") or "").lower(),
                    "date": sent_date,
                    "is_unread": is_unread,
                }
            )
        return messages

    @staticmethod
    def _parse_date(date_header):
        if not date_header:
            return None
        try:
            parsed = email.utils.parsedate_to_datetime(date_header)
            if parsed.tzinfo is not None:
                parsed = parsed.replace(tzinfo=None)
            return parsed
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _matches(
        message,
        sender_contains=None,
        subject_contains=None,
        older_than_days=None,
        unread_only=False,
    ):
        if sender_contains and sender_contains.lower() not in message["from"]:
            return False
        if subject_contains and subject_contains.lower() not in message["subject"]:
            return False
        if older_than_days is not None:
            if message["date"] is None:
                return False
            cutoff = datetime.utcnow() - timedelta(days=older_than_days)
            if message["date"] > cutoff:
                return False
        if unread_only and not message["is_unread"]:
            return False
        return True

    def _organize(self, rules, source_folder="INBOX", dry_run=False, limit=200):
        if not rules:
            raise ValueError("At least one rule is required")
        for rule in rules:
            if not rule.get("target_folder"):
                raise ValueError("Each rule requires a 'target_folder'")
            if not any(
                rule.get(key)
                for key in ("sender_contains", "subject_contains", "older_than_days")
            ):
                raise ValueError(
                    "Each rule requires at least one of: sender_contains, "
                    "subject_contains, older_than_days"
                )

        imap = self._connect()
        try:
            messages = self._fetch_headers(imap, source_folder, limit)
            moved_by_rule = {rule["target_folder"]: 0 for rule in rules}

            for message in messages:
                for rule in rules:
                    if self._matches(
                        message,
                        sender_contains=rule.get("sender_contains"),
                        subject_contains=rule.get("subject_contains"),
                        older_than_days=rule.get("older_than_days"),
                    ):
                        target_folder = rule["target_folder"]
                        moved_by_rule[target_folder] += 1
                        if not dry_run:
                            imap.uid("copy", message["uid"], target_folder)
                            imap.uid("store", message["uid"], "+FLAGS", "(\\Deleted)")
                        break

            if not dry_run:
                imap.expunge()

            return {
                "status": "success",
                "dry_run": dry_run,
                "scanned": len(messages),
                "moved_by_folder": moved_by_rule,
            }
        finally:
            imap.logout()

    def _purge(
        self,
        folder="INBOX",
        sender_contains=None,
        subject_contains=None,
        older_than_days=None,
        unread_only=False,
        permanent=False,
        dry_run=True,
        limit=200,
    ):
        if not any([sender_contains, subject_contains, older_than_days, unread_only]):
            raise ValueError(
                "At least one filter is required (sender_contains, subject_contains, "
                "older_than_days, or unread_only) to avoid purging an entire folder"
            )

        imap = self._connect()
        try:
            messages = self._fetch_headers(imap, folder, limit)
            matches = [
                message
                for message in messages
                if self._matches(
                    message,
                    sender_contains=sender_contains,
                    subject_contains=subject_contains,
                    older_than_days=older_than_days,
                    unread_only=unread_only,
                )
            ]

            if not dry_run:
                for message in matches:
                    if not permanent:
                        imap.uid("copy", message["uid"], self.trash_folder)
                    imap.uid("store", message["uid"], "+FLAGS", "(\\Deleted)")
                imap.expunge()

            return {
                "status": "success",
                "dry_run": dry_run,
                "permanent": permanent,
                "scanned": len(messages),
                "matched": len(matches),
            }
        finally:
            imap.logout()

    def get_actions_metadata(self):
        return [
            {
                "name": "email_list_folders",
                "description": "List the folders/mailboxes available in the configured email account.",
                "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": False,
                },
            },
            {
                "name": "email_organize",
                "description": (
                    "Automatically sort messages in a folder into other folders based on "
                    "rules matching sender, subject, or message age. Set dry_run to true "
                    "to preview matches without moving anything."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "rules": {
                            "type": "array",
                            "description": "Ordered list of rules; the first matching rule wins.",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "sender_contains": {
                                        "type": "string",
                                        "description": "Match if the From header contains this text",
                                    },
                                    "subject_contains": {
                                        "type": "string",
                                        "description": "Match if the Subject header contains this text",
                                    },
                                    "older_than_days": {
                                        "type": "integer",
                                        "description": "Match if the message is older than this many days",
                                    },
                                    "target_folder": {
                                        "type": "string",
                                        "description": "Folder to move matching messages into",
                                    },
                                },
                                "required": ["target_folder"],
                            },
                        },
                        "source_folder": {
                            "type": "string",
                            "description": "Folder to scan for messages to organize (default INBOX)",
                        },
                        "dry_run": {
                            "type": "boolean",
                            "description": "If true, only report what would be moved (default false)",
                        },
                        "limit": {
                            "type": "integer",
                            "description": f"Maximum number of recent messages to scan (default 200, max {MAX_MESSAGES})",
                        },
                    },
                    "required": ["rules"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "email_purge",
                "description": (
                    "Delete or archive messages in a folder that match the given filters. "
                    "At least one filter is required. Defaults to a dry run and to moving "
                    "matches to the trash folder rather than permanently deleting them."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "folder": {
                            "type": "string",
                            "description": "Folder to purge messages from (default INBOX)",
                        },
                        "sender_contains": {
                            "type": "string",
                            "description": "Match if the From header contains this text",
                        },
                        "subject_contains": {
                            "type": "string",
                            "description": "Match if the Subject header contains this text",
                        },
                        "older_than_days": {
                            "type": "integer",
                            "description": "Match if the message is older than this many days",
                        },
                        "unread_only": {
                            "type": "boolean",
                            "description": "Only match unread messages (default false)",
                        },
                        "permanent": {
                            "type": "boolean",
                            "description": (
                                "If true, delete matches permanently instead of moving "
                                "them to the trash folder (default false)"
                            ),
                        },
                        "dry_run": {
                            "type": "boolean",
                            "description": "If true (default), only report what would be purged",
                        },
                        "limit": {
                            "type": "integer",
                            "description": f"Maximum number of recent messages to scan (default 200, max {MAX_MESSAGES})",
                        },
                    },
                    "required": [],
                    "additionalProperties": False,
                },
            },
        ]

    def get_config_requirements(self):
        return {
            "imap_server": {
                "type": "string",
                "label": "IMAP Server",
                "description": "IMAP server hostname, e.g. imap.gmail.com",
                "required": True,
                "order": 1,
            },
            "imap_port": {
                "type": "string",
                "label": "IMAP Port",
                "description": "IMAP server port (default 993)",
                "required": False,
                "order": 2,
            },
            "email": {
                "type": "string",
                "label": "Email Address",
                "description": "The email account to connect to",
                "required": True,
                "order": 3,
            },
            "password": {
                "type": "string",
                "label": "Password",
                "description": "App password or account password for IMAP authentication",
                "required": True,
                "secret": True,
                "order": 4,
            },
            "use_ssl": {
                "type": "boolean",
                "label": "Use SSL",
                "description": "Connect over SSL (default true)",
                "required": False,
                "order": 5,
            },
            "trash_folder": {
                "type": "string",
                "label": "Trash Folder",
                "description": "Folder used when purging without permanent deletion (default Trash)",
                "required": False,
                "order": 6,
            },
        }
