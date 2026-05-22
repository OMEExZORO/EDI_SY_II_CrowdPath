"""
FastAPI application entry-point.

Run with:  uvicorn main:app --reload
"""

import hashlib
import secrets
import uuid
from datetime import datetime, date, timedelta
from collections import defaultdict

import jwt
from fastapi import FastAPI, HTTPException, Depends, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from database import Base, engine
from routes import router

# Create all tables on startup
Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="Smart Cane Indoor Navigation API",
    description="Crowdsourced building map management for visually impaired navigation.",
    version="1.0.0",
)

# CORS — allow all origins during development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)


# ── JWT Auth Config ─────────────────────────────────────────────────────────

ADMIN_USERNAME = "crowdpath_admin"
ADMIN_PASSWORD_HASH = hashlib.sha256("admin123".encode()).hexdigest()
JWT_SECRET = secrets.token_hex(32)
JWT_ALGORITHM = "HS256"
JWT_EXPIRY_HOURS = 8

security = HTTPBearer()


def create_token(username: str) -> str:
    payload = {
        "sub": username,
        "exp": datetime.utcnow() + timedelta(hours=JWT_EXPIRY_HOURS),
        "iat": datetime.utcnow(),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def verify_token(
    credentials: HTTPAuthorizationCredentials = Depends(security),
) -> str:
    try:
        payload = jwt.decode(
            credentials.credentials, JWT_SECRET, algorithms=[JWT_ALGORITHM]
        )
        return payload["sub"]
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="Invalid token")


# ── Health Check ────────────────────────────────────────────────────────────


@app.get("/", tags=["health"])
def health_check() -> dict:
    return {"status": "ok", "service": "smart-cane-backend"}


# ── Admin Login ─────────────────────────────────────────────────────────────


@app.post("/api/admin/login", tags=["admin"])
async def admin_login(payload: dict):
    username = payload.get("username", "")
    password = payload.get("password", "")
    password_hash = hashlib.sha256(password.encode()).hexdigest()

    if username != ADMIN_USERNAME or password_hash != ADMIN_PASSWORD_HASH:
        raise HTTPException(status_code=401, detail="Invalid credentials")

    token = create_token(username)
    return {"token": token, "expires_in_hours": JWT_EXPIRY_HOURS}


# ── Edge Block Data Store ──────────────────────────────────────────────────

_edge_blocks: list = []


def is_block_active_today(block: dict) -> bool:
    """Check if a block applies right now."""
    if not block.get("is_active", True):
        return False

    today = date.today().isoformat()
    block_type = block["block_type"]

    if block_type == "TODAY":
        return block["blocked_from"] == today
    elif block_type == "DATE_RANGE":
        return block["blocked_from"] <= today <= block["blocked_until"]
    elif block_type == "INDEFINITE":
        return block["blocked_from"] <= today

    return False


# ── Admin Block Endpoints (JWT-protected) ──────────────────────────────────


@app.post("/api/admin/blocks", status_code=201, tags=["admin"])
async def create_block(payload: dict, admin: str = Depends(verify_token)):
    block_type = payload.get("block_type")
    if block_type not in ("TODAY", "DATE_RANGE", "INDEFINITE"):
        raise HTTPException(
            status_code=400,
            detail="block_type must be TODAY, DATE_RANGE, or INDEFINITE",
        )

    today = date.today().isoformat()
    blocked_from = today
    blocked_until = None

    if block_type == "DATE_RANGE":
        blocked_from = payload.get("blocked_from", today)
        blocked_until = payload.get("blocked_until")
        if not blocked_until:
            raise HTTPException(
                status_code=400, detail="DATE_RANGE requires blocked_until"
            )
        if blocked_from > blocked_until:
            raise HTTPException(
                status_code=400,
                detail="blocked_from must be before blocked_until",
            )
    elif block_type == "TODAY":
        blocked_from = today
        blocked_until = today

    block = {
        "block_id": str(uuid.uuid4()),
        "building_id": payload["building_id"],
        "edge_id": payload["edge_id"],
        "reason": payload.get("reason", "No reason given"),
        "block_type": block_type,
        "blocked_from": blocked_from,
        "blocked_until": blocked_until,
        "created_by": admin,
        "created_at": datetime.utcnow().isoformat(),
        "is_active": True,
    }
    _edge_blocks.append(block)
    print(f"[ADMIN] Block created: {block}")
    return block


@app.get("/api/admin/blocks/{building_id}", tags=["admin"])
async def list_blocks(building_id: str, admin: str = Depends(verify_token)):
    blocks = [b for b in _edge_blocks if b["building_id"] == building_id]
    for b in blocks:
        b["currently_active"] = is_block_active_today(b)
    return {"building_id": building_id, "blocks": blocks}


@app.delete("/api/admin/blocks/{block_id}", tags=["admin"])
async def remove_block(block_id: str, admin: str = Depends(verify_token)):
    for block in _edge_blocks:
        if block["block_id"] == block_id:
            block["is_active"] = False
            print(f"[ADMIN] Block {block_id} deactivated by {admin}")
            return {"message": "Block removed", "block_id": block_id}
    raise HTTPException(status_code=404, detail="Block not found")


@app.get("/api/maps/{building_id}/active-blocks", tags=["maps"])
async def get_active_blocks_for_app(building_id: str):
    """Public endpoint — called by Android app to seed D* Lite before navigation."""
    active_edge_ids = [
        b["edge_id"]
        for b in _edge_blocks
        if b["building_id"] == building_id and is_block_active_today(b)
    ]
    return {"building_id": building_id, "blocked_edge_ids": list(set(active_edge_ids))}


# ── Obstacle Reporting ──────────────────────────────────────────────────────

_obstacle_reports: dict = defaultdict(list)
FLAGGING_THRESHOLD = 3
FLAGGING_WINDOW_HOURS = 48


@app.post("/api/maps/obstacle-report", status_code=204, tags=["obstacles"])
async def receive_obstacle_report(report: dict):
    edge_id = report.get("edge_id")
    building_id = report.get("building_id")

    if not edge_id or not building_id:
        raise HTTPException(
            status_code=400, detail="edge_id and building_id required"
        )

    key = f"{building_id}::{edge_id}"
    now = datetime.utcnow()
    cutoff = now - timedelta(hours=FLAGGING_WINDOW_HOURS)

    _obstacle_reports[key].append(now)
    _obstacle_reports[key] = [t for t in _obstacle_reports[key] if t > cutoff]

    report_count = len(_obstacle_reports[key])
    print(
        f"[OBSTACLE] edge={edge_id} building={building_id} reports_in_window={report_count}"
    )

    if report_count >= FLAGGING_THRESHOLD:
        print(
            f"[FLAG] Edge {edge_id} in {building_id} flagged — {report_count} reports. Needs volunteer review."
        )

    return


# ── Admin Portal (served as HTML) ──────────────────────────────────────────


@app.get("/admin", response_class=HTMLResponse, tags=["admin"])
async def admin_portal():
    import os

    portal_path = os.path.join(os.path.dirname(__file__), "admin_portal.html")
    with open(portal_path, "r", encoding="utf-8") as f:
        return f.read()
