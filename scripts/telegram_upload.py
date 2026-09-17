#!/usr/bin/env python3
"""
Upload a build artifact (APK / AAB / zip) to a Telegram chat, together with the full
build information, using MTProto instead of the HTTP Bot API.

Why MTProto
-----------
https://api.telegram.org/bot<token>/sendDocument hard-caps uploads at 50 MB (and
getFile downloads at 20 MB), so the ~120 MB AxBrowser APK can never be sent that way -
it is rejected with 413 Request Entity Too Large. Telethon speaks MTProto directly,
where the same bot can upload files up to 2 GB (4 GB with Premium), and it logs in
with nothing more than the api_id / api_hash from https://my.telegram.org/apps plus
the bot token from @BotFather.

Credentials (read from the environment; the first name wins, the aliases let the
same script run from GitHub Actions secrets, .env files or a hand-run shell)
------------------------------------------------------------------------------
TELEGRAM_API_ID / BOT_API_ID            app id   (https://my.telegram.org/apps)
TELEGRAM_API_HASH / BOT_API_HASH        app hash (https://my.telegram.org/apps)
TELEGRAM_BOT_TOKEN / BOT_TOKEN          token from @BotFather
TELEGRAM_CHAT_ID / CHAT_ID              numeric id (-100...) or @username
TELEGRAM_SESSION_STRING                 optional StringSession; preferred over the
                                        session file and skips a fresh
                                        importBotAuthorization on every build
--session-file PATH                     StringSession file: read when it exists and
                                        written after a fresh bot login, so the
                                        login only happens once and later builds
                                        reuse the saved session

Fallback chain (each step only runs when the one before it cannot deliver)
------------------------------------------------------------------------
1. MTProto document upload - the whole file, up to 2GB.
2. Bot API sendDocument   - only possible up to 50MB.
3. Bot API parts          - a >50MB artifact split into sub-50MB documents, plus the
                            one-line command that joins them back together.
4. Info message           - the full build info plus the GitHub release link.
Every step reports why the previous one did not happen, in the chat as well as the log,
and the failure of any step still exits 0 as long as one of them delivered the build.

Character limits (Telegram counts UTF-16 code units, so one emoji = 2):
  - document caption : 1024  -> --caption-file is truncated, never rejected
  - message text     : 4096
When the caption had to be truncated, the untouched text is sent as a follow-up
message so nothing is silently lost.

Usage
-----
  python scripts/telegram_upload.py --file app.apk --caption-file /tmp/caption.txt
  python scripts/telegram_upload.py --file app.apk --caption-file c.txt \
      --fallback-url https://github.com/owner/repo/releases/download/nightly/app.apk
  python scripts/telegram_upload.py --file app.apk --caption-file c.txt \
      --session-file /tmp/telegram.session
  python scripts/telegram_upload.py --make-session --session-out session.txt
  python scripts/telegram_upload.py --check --send-test   # doctor: login + test message
  python scripts/telegram_upload.py --file app.apk --caption-file c.txt --dry-run

Exit codes: 0 = uploaded (or a documented fallback delivered it), 2 = nothing sent.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import math
import mimetypes
import os
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

CAPTION_LIMIT = 1024  # Telegram document caption
MESSAGE_LIMIT = 4096  # Telegram message text
BOT_API_DOC_LIMIT = 50 * 1024 * 1024  # Bot API sendDocument ceiling
BOT_API_PART_SIZE = 45 * 1024 * 1024  # part size that stays safely under that ceiling
APK_MIME = "application/vnd.android.package-archive"

TRUNCATED_MARK = "\n… (truncated)"


def log(msg: str) -> None:
    print(msg, flush=True)


def warn(msg: str) -> None:
    print(f"::warning::{msg}", flush=True)


def utf16_len(text: str) -> int:
    """Telegram measures captions in UTF-16 code units, not Python characters."""
    return len(text.encode("utf-16-le")) // 2


def truncate(text: str, limit: int) -> str:
    """Cut `text` to at most `limit` UTF-16 code units, keeping a visible marker."""
    if utf16_len(text) <= limit:
        return text
    budget = limit - utf16_len(TRUNCATED_MARK)
    out: list[str] = []
    used = 0
    for ch in text:
        size = utf16_len(ch)
        if used + size > budget:
            break
        out.append(ch)
        used += size
    return "".join(out) + TRUNCATED_MARK


def human_bytes(n: int) -> str:
    mb = n / (1024 * 1024)
    return f"{mb / 1024:.2f} GB" if mb >= 1024 else f"{mb:.2f} MB"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def guess_mime(path: Path) -> str:
    if path.suffix.lower() == ".apk":
        return APK_MIME
    return mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def env(*names: str) -> str:
    """First non-empty environment variable among `names`."""
    for name in names:
        value = os.environ.get(name, "").strip()
        if value:
            return value
    return ""


def parse_chat(raw: str):
    """Telethon takes a real int for numeric ids, a username string otherwise."""
    raw = raw.strip()
    if raw.lstrip("-").isdigit():
        return int(raw)
    return raw


def read_caption(args: argparse.Namespace) -> str:
    if args.caption_file:
        return Path(args.caption_file).read_text(encoding="utf-8").rstrip()
    if args.caption:
        return args.caption
    return ""


# --------------------------------------------------------------------------------------
# MTProto (Telethon)
# --------------------------------------------------------------------------------------

def save_session(args: argparse.Namespace, client) -> None:
    """Persist a freshly created session so the next build skips the bot login."""
    if not getattr(args, "session_file", None):
        return
    try:
        Path(args.session_file).write_text(client.session.save(), encoding="utf-8")
        log(f"MTProto session created and saved to {args.session_file}")
    except Exception as exc:  # noqa: BLE001 - a missing session is not worth failing a build
        warn(f"Could not save the MTProto session to {args.session_file}: {exc}")


async def mtproto_login(client, cfg: dict):
    """Sign in with the saved session when there is one, else as the bot."""
    if cfg["session"]:
        await client.connect()
        if not await client.is_user_authorized():
            raise RuntimeError(
                "the saved Telegram session is not authorised (revoked, or created for "
                "another api_id)"
            )
        return False
    # importBotAuthorization - no phone number or login code needed.
    await client.start(bot_token=cfg["bot_token"])
    return True


async def mtproto_check(args: argparse.Namespace, cfg: dict) -> None:
    """Doctor mode: prove the credentials work and show where the file would go."""
    from telethon import TelegramClient
    from telethon.sessions import StringSession

    session = StringSession(cfg["session"]) if cfg["session"] else StringSession()
    client = TelegramClient(session, int(cfg["api_id"]), cfg["api_hash"])
    client.parse_mode = None
    try:
        fresh = await mtproto_login(client, cfg)
        me = await client.get_me()
        log(f"MTProto login OK: id={me.id} username=@{getattr(me, 'username', None)} bot={me.bot}")
        if fresh:
            save_session(args, client)
        entity = await client.get_entity(parse_chat(cfg["chat"]))
        log(
            f"Chat OK: {type(entity).__name__} id={getattr(entity, 'id', None)} "
            f"title={getattr(entity, 'title', None)!r}"
        )
        if args.send_test:
            await client.send_message(entity, "AxBrowser Telegram check: MTProto login works.")
            log("Test message sent.")
        log("Doctor: everything needed for the APK upload works.")
    finally:
        await client.disconnect()


async def mtproto_send(args: argparse.Namespace, cfg: dict) -> None:
    from telethon import TelegramClient
    from telethon.sessions import StringSession
    from telethon.tl.types import DocumentAttributeFilename
    from telethon.errors import FloodWaitError

    session = StringSession(cfg["session"]) if cfg["session"] else StringSession()
    client = TelegramClient(session, int(cfg["api_id"]), cfg["api_hash"])
    client.parse_mode = None  # keep the caption exactly as written

    try:
        fresh = await mtproto_login(client, cfg)
        if fresh:
            save_session(args, client)

        me = await client.get_me()
        log(f"MTProto: signed in as @{getattr(me, 'username', None) or me.id}")

        entity = await client.get_entity(parse_chat(cfg["chat"]))
        path = Path(args.file)
        total = path.stat().st_size
        state = {"pct": -1, "t0": time.time()}

        def progress(sent: int, expected: int) -> None:
            pct = int(sent * 100 / expected) if expected else 0
            if pct >= state["pct"] + 10 or pct == 100:
                state["pct"] = pct
                log(
                    f"  uploading {pct:3d}%  {human_bytes(sent)} / {human_bytes(expected)}"
                    f"  ({time.time() - state['t0']:.0f}s)"
                )

        for attempt in range(1, 4):
            try:
                log(f"MTProto: uploading {path.name} ({human_bytes(total)}) as a document")
                await client.send_file(
                    entity,
                    str(path),
                    caption=cfg["caption"],
                    force_document=True,
                    attributes=[DocumentAttributeFilename(path.name)],
                    progress_callback=progress,
                )
                break
            except FloodWaitError as exc:
                if attempt == 3:
                    raise
                warn(f"Telegram flood wait {exc.seconds}s, retrying ({attempt}/3)")
                await asyncio.sleep(exc.seconds + 2)
        log(f"MTProto: sent {path.name} ({human_bytes(total)}) in {time.time() - state['t0']:.0f}s")
    finally:
        await client.disconnect()


async def make_session(args: argparse.Namespace, cfg: dict) -> None:
    from telethon import TelegramClient
    from telethon.sessions import StringSession

    if not cfg["api_id"].isdigit():
        raise SystemExit(f"api id must be a number, got {cfg['api_id']!r}")
    client = TelegramClient(StringSession(), int(cfg["api_id"]), cfg["api_hash"])
    try:
        if args.login_user:
            if not sys.stdin.isatty():
                raise SystemExit(
                    "--login-user needs an interactive terminal (phone + login code); "
                    "run it on your own machine, not in CI."
                )
            await client.start()
        else:
            await client.start(bot_token=cfg["bot_token"])
        me = await client.get_me()
        value = client.session.save()
        log(f"Signed in as @{getattr(me, 'username', None) or me.id} (bot={me.bot})")
        if args.session_out:
            Path(args.session_out).write_text(value, encoding="utf-8")
            log(f"Session string written to {args.session_out}")
        else:
            log("Session string (store it as the TELEGRAM_SESSION_STRING secret):")
            log(value)
    finally:
        await client.disconnect()


# --------------------------------------------------------------------------------------
# Bot API (HTTP) fallback - only useful below 50 MB
# --------------------------------------------------------------------------------------

def _multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    body = bytearray()
    for name, value in fields.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        body += f"{value}\r\n".encode()
    for name, (filename, payload, mime) in files.items():
        body += f"--{boundary}\r\n".encode()
        body += (
            f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
            f"Content-Type: {mime}\r\n\r\n"
        ).encode()
        body += payload + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def bot_api_call(token: str, method: str, fields: dict, files: dict | None = None) -> str:
    if files:
        payload, content_type = _multipart(fields, files)
    else:
        payload = urllib.parse.urlencode(fields).encode()
        content_type = "application/x-www-form-urlencoded"
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/{method}",
        data=payload,
        headers={"Content-Type": content_type},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read().decode("utf-8", "replace")


def bot_api_send_parts(
    cfg: dict,
    path: Path,
    note: str | None = None,
) -> bool:
    """Split a too-large artifact into sub-50MB documents - the only way the plain Bot API
    can carry a >50MB APK at all, so the file still reaches the chat when MTProto cannot."""
    size = path.stat().st_size
    count = math.ceil(size / BOT_API_PART_SIZE)
    log(f"Bot API: splitting into {count} parts (max {human_bytes(BOT_API_PART_SIZE)} each)")
    tmpdir = Path(tempfile.mkdtemp(prefix="ax-telegram-parts-"))
    try:
        parts: list[Path] = []
        with path.open("rb") as src:
            for index in range(1, count + 1):
                part = tmpdir / f"{path.name}.part{index:02d}"
                part.write_bytes(src.read(BOT_API_PART_SIZE))
                parts.append(part)

        for index, part in enumerate(parts, start=1):
            header = f"⚠️ {note}\n\n" if note and index == 1 else ""
            caption = truncate(
                f"{header}{path.name} — part {index} of {count} "
                f"({human_bytes(part.stat().st_size)})",
                CAPTION_LIMIT,
            )
            log(f"Bot API: part {index}/{count} ({human_bytes(part.stat().st_size)})")
            try:
                bot_api_call(
                    cfg["bot_token"],
                    "sendDocument",
                    {"chat_id": cfg["chat"], "caption": caption},
                    {"document": (part.name, part.read_bytes(), guess_mime(path))},
                )
            except Exception as exc:  # noqa: BLE001 - reported, then the link path takes over
                warn(f"Bot API part {index}/{count} failed: {exc}")
                return False
            log(f"  part {index}/{count}: sent")

        join = (
            f"Join the {count} parts back into one APK:\n\n"
            f"Linux/macOS:  cat {path.name}.part* > {path.name}\n"
            f"Windows:  copy /b {path.name}.part* {path.name}\n\n"
            "(Telegram renames downloads to the part filenames, so keep them together.)"
        )
        try:
            bot_api_call(
                cfg["bot_token"],
                "sendMessage",
                {"chat_id": cfg["chat"], "text": truncate(join, MESSAGE_LIMIT)},
            )
        except Exception as exc:  # noqa: BLE001
            warn(f"Bot API join instructions failed: {exc}")
        return True
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def bot_api_fallback(
    cfg: dict,
    path: Path,
    caption: str,
    fallback_url: str | None,
    note: str | None = None,
    allow_split: bool = True,
) -> bool:
    size = path.stat().st_size
    if size <= BOT_API_DOC_LIMIT:
        log(f"Bot API: sending {path.name} as a document ({human_bytes(size)})")
        try:
            log(bot_api_call(
                cfg["bot_token"],
                "sendDocument",
                {"chat_id": cfg["chat"], "caption": caption},
                {"document": (path.name, path.read_bytes(), guess_mime(path))},
            )[:400])
            return True
        except urllib.error.HTTPError as exc:
            warn(f"Bot API sendDocument failed: {exc.code} {exc.read()[:200]!r}")
        except Exception as exc:  # noqa: BLE001 - reported, then we try the text path
            warn(f"Bot API sendDocument failed: {exc}")
    else:
        warn(
            f"Bot API cannot carry {human_bytes(size)} in one message "
            f"(sendDocument limit is {human_bytes(BOT_API_DOC_LIMIT)})."
        )
        if allow_split and bot_api_send_parts(cfg, path, note=note):
            return True

    if fallback_url:
        # Lead with why the real upload did not happen: reading it in the Telegram chat is
        # how this gets diagnosed from a phone instead of from the Actions log.
        header = f"⚠️ {note}\n\n" if note else ""
        text = truncate(f"{header}{caption}\n\nDownload: {fallback_url}", MESSAGE_LIMIT)
        log("Bot API: sending the build info + download link instead")
        try:
            log(bot_api_call(cfg["bot_token"], "sendMessage", {"chat_id": cfg["chat"], "text": text})[:400])
            return True
        except Exception as exc:  # noqa: BLE001
            warn(f"Bot API sendMessage failed: {exc}")
    return False


# --------------------------------------------------------------------------------------

# Telegram api_hash values are 32 hex characters. A shorter one means the value was
# cut short somewhere (a copy/paste, or a message line that wrapped), and ANY length
# mismatch makes MTProto reject the login - so say that out loud instead of letting it
# fail silently into the Bot API fallback.
API_HASH_LEN = 32


def load_config() -> dict:
    return {
        "api_id": env("TELEGRAM_API_ID", "BOT_API_ID", "AX_TELEGRAM_API_ID"),
        "api_hash": env("TELEGRAM_API_HASH", "BOT_API_HASH", "AX_TELEGRAM_API_HASH"),
        "bot_token": env("TELEGRAM_BOT_TOKEN", "BOT_TOKEN", "TELEGRAM_TOKEN", "BOT_API_TOKEN"),
        "chat": env("TELEGRAM_CHAT_ID", "BOT_CHAT_ID", "CHAT_ID", "TELEGRAM_CHANNEL_ID"),
        "session": env("TELEGRAM_SESSION_STRING", "BOT_SESSION_STRING"),
    }


def check_hash(cfg: dict) -> None:
    """Warn (with the actual length) when the api_hash cannot possibly be right."""
    value, length = cfg["api_hash"], len(cfg["api_hash"])
    if length and length != API_HASH_LEN:
        warn(
            f"api hash is {length} characters, but Telegram api_hash values are always "
            f"{API_HASH_LEN} - the value is incomplete and MTProto login will be refused. "
            "Copy it in full from https://my.telegram.org/apps."
        )
    elif length and not all(c in "0123456789abcdefABCDEF" for c in value):
        warn("api hash is not hexadecimal - check for a stray character.")


def missing_pieces(cfg: dict) -> list[str]:
    names = {
        "api_id": "TELEGRAM_API_ID / BOT_API_ID",
        "api_hash": "TELEGRAM_API_HASH / BOT_API_HASH",
        "bot_token": "TELEGRAM_BOT_TOKEN / BOT_TOKEN",
        "chat": "TELEGRAM_CHAT_ID / CHAT_ID",
    }
    return [label for key, label in names.items() if not cfg[key]]


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload a build artifact to Telegram over MTProto")
    parser.add_argument("--file", help="artifact to upload")
    parser.add_argument("--caption", default="", help="caption text (<=1024 units)")
    parser.add_argument("--caption-file", help="file holding the caption text")
    parser.add_argument("--fallback-url", help="download link to send if the upload is impossible")
    parser.add_argument("--make-session", action="store_true", help="print/store a StringSession and exit")
    parser.add_argument("--login-user", action="store_true", help="with --make-session: log in as a user (interactive)")
    parser.add_argument("--session-out", help="with --make-session: write the session string to this file")
    parser.add_argument(
        "--session-file",
        help="StringSession file: reused when present, written after a fresh bot login",
    )
    parser.add_argument("--check", action="store_true", help="doctor: verify login + chat and exit")
    parser.add_argument("--send-test", action="store_true", help="with --check: send a short test message")
    parser.add_argument("--dry-run", action="store_true", help="validate caption/limits and print what would be sent")
    parser.add_argument(
        "--no-split",
        action="store_true",
        help="never split a too-large artifact into sub-50MB Bot API parts",
    )
    args = parser.parse_args()

    cfg = load_config()
    check_hash(cfg)

    # A saved session (secret first, then file) removes the per-build bot login.
    if not cfg["session"] and args.session_file and Path(args.session_file).is_file():
        saved = Path(args.session_file).read_text(encoding="utf-8").strip()
        if saved:
            cfg["session"] = saved
            log(f"Reusing the MTProto session saved in {args.session_file}")

    if args.make_session:
        if not cfg["api_id"] or not cfg["api_hash"]:
            raise SystemExit("--make-session needs an api id and api hash")
        if not args.login_user and not cfg["bot_token"]:
            raise SystemExit("--make-session needs a bot token (or --login-user)")
        asyncio.run(make_session(args, cfg))
        return 0

    if args.check:
        missing = missing_pieces(cfg)
        if missing:
            for label in missing:
                print(f"::error::Missing Telegram configuration: {label}", flush=True)
            return 3
        try:
            asyncio.run(mtproto_check(args, cfg))
            return 0
        except ImportError:
            print("::error::Telethon is not installed (pip install telethon)", flush=True)
            return 3
        except Exception as exc:  # noqa: BLE001 - doctor mode reports the real reason
            print(f"::error::MTProto check failed: {type(exc).__name__}: {exc}", flush=True)
            return 3

    if not args.file:
        raise SystemExit("--file is required")
    path = Path(args.file)
    if not path.is_file():
        raise SystemExit(f"File not found: {path}")

    cfg["caption"] = read_caption(args)
    size = path.stat().st_size
    truncated = utf16_len(cfg["caption"]) > CAPTION_LIMIT
    if truncated:
        warn(f"Caption is {utf16_len(cfg['caption'])} units; trimming to {CAPTION_LIMIT}")
    cfg["caption"] = truncate(cfg["caption"], CAPTION_LIMIT)

    log("=" * 70)
    log(f"Artifact : {path} ({human_bytes(size)}, {size} bytes)")
    log(f"MIME     : {guess_mime(path)}")
    log(f"SHA-256  : {sha256_of(path)}")
    log(f"Chat     : {cfg['chat'] or '(unset)'}")
    log(f"Caption  : {utf16_len(cfg['caption'])}/{CAPTION_LIMIT} UTF-16 units")
    log("-" * 70)
    log(cfg["caption"])
    log("=" * 70)

    missing = missing_pieces(cfg)
    note: str | None = None

    if args.dry_run:
        for label in missing:
            warn(f"Not configured: {label} - the upload would fall back to the Bot API")
        log("Dry run: nothing sent.")
        return 0

    mtproto_ready = not missing
    if mtproto_ready:
        try:
            if not cfg["session"]:
                log("No saved MTProto session yet - logging in as the bot for this run.")
            asyncio.run(mtproto_send(args, cfg))
            if truncated and cfg["bot_token"] and cfg["chat"]:
                bot_api_call(
                    cfg["bot_token"],
                    "sendMessage",
                    {"chat_id": cfg["chat"], "text": truncate("[full build info]\n\n" + read_caption(args), MESSAGE_LIMIT)},
                )
            return 0
        except ImportError:
            note = "Telethon is not installed on the runner."
            warn(f"{note} Falling back to the Bot API")
        except Exception as exc:  # noqa: BLE001 - any MTProto failure degrades, never crashes the build
            note = f"MTProto upload failed: {type(exc).__name__}: {exc}"
            warn(f"{note} Falling back to the Bot API")
    else:
        note = "MTProto credentials are incomplete (missing: " + ", ".join(missing) + ")."
        warn(note + " Falling back to the Bot API")
        for label in missing:
            warn(f"  missing: {label}")

    if cfg["bot_token"] and cfg["chat"]:
        if bot_api_fallback(
            cfg,
            path,
            cfg["caption"],
            args.fallback_url,
            note=note,
            allow_split=not args.no_split,
        ):
            return 0
    else:
        warn("Bot API fallback also needs TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID")

    warn("Telegram delivery failed; the GitHub release/artifact still has the APK.")
    return 2


if __name__ == "__main__":
    sys.exit(main())
