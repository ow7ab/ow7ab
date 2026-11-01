"""Offline-first preparation only; no network requests or financial postings.

Drafts are saved in a separate SQLite file. They NEVER change pmp_cash.db.
The future server must authenticate, authorize and validate each draft before posting.
"""
import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

DRAFT_KINDS = frozenset({'invoice', 'stock_receipt', 'cash_movement'})
MAX_PAYLOAD_BYTES = 64 * 1024


def _now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def initialize_drafts(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(path) as con:
        con.execute('''CREATE TABLE IF NOT EXISTS offline_drafts (
            draft_id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,
            author TEXT NOT NULL, kind TEXT NOT NULL,
            payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'draft'
                CHECK(status IN ('draft', 'submitted', 'rejected')),
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            server_reference TEXT,
            CHECK(length(branch_id) BETWEEN 1 AND 64),
            CHECK(length(author) BETWEEN 1 AND 128)
        )''')
        con.execute('CREATE INDEX IF NOT EXISTS idx_offline_drafts_branch_status ON offline_drafts(branch_id,status)')
    return path


def create_draft(path, *, branch_id, author, kind, payload):
    if kind not in DRAFT_KINDS:
        raise ValueError('Unsupported draft type')
    branch_id, author = str(branch_id).strip(), str(author).strip()
    if not 1 <= len(branch_id) <= 64 or not 1 <= len(author) <= 128:
        raise ValueError('Valid branch and author are required')
    if not isinstance(payload, dict):
        raise ValueError('Draft payload must be a JSON object')
    raw = json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
    if len(raw.encode('utf-8')) > MAX_PAYLOAD_BYTES:
        raise ValueError('Draft exceeds size limit')
    draft_id = str(uuid.uuid4())  # stable idempotency key for future API requests
    timestamp = _now()
    initialize_drafts(path)
    with sqlite3.connect(path) as con:
        con.execute('''INSERT INTO offline_drafts
            (draft_id,branch_id,author,kind,payload_json,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?)''', (draft_id, branch_id, author, kind, raw, timestamp, timestamp))
    return draft_id


def list_drafts(path, *, branch_id, status=None):
    initialize_drafts(path)
    sql = '''SELECT draft_id,branch_id,author,kind,payload_json,status,created_at,
             updated_at,server_reference FROM offline_drafts WHERE branch_id=?'''
    params = [branch_id]
    if status is not None:
        if status not in ('draft', 'submitted', 'rejected'):
            raise ValueError('Invalid draft status')
        sql += ' AND status=?'
        params.append(status)
    sql += ' ORDER BY created_at DESC, draft_id DESC'
    with sqlite3.connect(path) as con:
        rows = con.execute(sql, params).fetchall()
    keys = ('draft_id','branch_id','author','kind','payload','status','created_at','updated_at','server_reference')
    return [dict(zip(keys, row[:4] + (json.loads(row[4]),) + row[5:])) for row in rows]


def mark_server_result(path, *, draft_id, branch_id, accepted, server_reference=None):
    """Only call after authenticated server response; never posts local accounting entries."""
    if accepted and not server_reference:
        raise ValueError('Accepted drafts require a server reference')
    initialize_drafts(path)
    with sqlite3.connect(path) as con:
        cur = con.execute('''UPDATE offline_drafts SET status=?,server_reference=?,updated_at=?
            WHERE draft_id=? AND branch_id=? AND status='draft' ''',
            ('submitted' if accepted else 'rejected', server_reference, _now(), draft_id, branch_id))
        if cur.rowcount != 1:
            raise ValueError('Draft missing or already processed')


def update_draft(path, *, draft_id, branch_id, author, kind, payload):
    """Edit only own unsubmitted draft; never touches financial tables."""
    if kind not in DRAFT_KINDS:
        raise ValueError('Unsupported draft type')
    raw = json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
    if not isinstance(payload, dict) or len(raw.encode('utf-8')) > MAX_PAYLOAD_BYTES:
        raise ValueError('Invalid draft payload')
    initialize_drafts(path)
    with sqlite3.connect(path) as con:
        result = con.execute("""UPDATE offline_drafts SET kind=?,payload_json=?,updated_at=?
            WHERE draft_id=? AND branch_id=? AND author=? AND status='draft' """,
            (kind, raw, _now(), draft_id, branch_id, author))
        if result.rowcount != 1:
            raise ValueError('Draft not found or not editable')


def delete_draft(path, *, draft_id, branch_id, author):
    initialize_drafts(path)
    with sqlite3.connect(path) as con:
        result = con.execute("""DELETE FROM offline_drafts WHERE draft_id=? AND branch_id=?
            AND author=? AND status='draft' """, (draft_id, branch_id, author))
        if result.rowcount != 1:
            raise ValueError('Draft not found or not deletable')
