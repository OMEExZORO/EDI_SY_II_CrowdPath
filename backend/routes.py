"""
REST API routes for building map management.
"""

from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from database import get_db
from merge import merge_maps
from models import Building, MapUpdate
from schemas import (
    BuildingCreate,
    BuildingListItem,
    BuildingMapData,
    BuildingResponse,
    BuildingUpdate,
    ErrorResponse,
    MergeRequest,
)

router = APIRouter(prefix="/api/maps", tags=["maps"])


# ── Helpers ────────────────────────────────────────────────────────────────


def _get_building_or_404(building_id: str, db: Session) -> Building:
    building = db.query(Building).filter(Building.id == building_id).first()
    if building is None:
        raise HTTPException(status_code=404, detail=f"Building '{building_id}' not found")
    return building


# ── Upload ─────────────────────────────────────────────────────────────────


@router.post(
    "/upload",
    response_model=BuildingResponse,
    status_code=201,
    responses={400: {"model": ErrorResponse}, 409: {"model": ErrorResponse}},
)
def upload_map(payload: BuildingCreate, db: Session = Depends(get_db)) -> Building:
    """Upload a new building map. Validates graph structure."""
    existing = db.query(Building).filter(Building.id == payload.id).first()
    if existing is not None:
        raise HTTPException(
            status_code=409,
            detail=f"Building '{payload.id}' already exists. Use PUT to update.",
        )

    building = Building(
        id=payload.id,
        name=payload.name,
        uploaded_by=payload.uploaded_by,
        map_data=payload.map_data.model_dump(),
        is_public=payload.is_public,
    )
    db.add(building)

    db.add(
        MapUpdate(
            building_id=payload.id,
            update_type="CREATE",
            changes={"node_count": len(payload.map_data.nodes), "edge_count": len(payload.map_data.edges)},
        )
    )
    db.commit()
    db.refresh(building)
    return building


# ── List ───────────────────────────────────────────────────────────────────


@router.get("/list", response_model=List[BuildingListItem])
def list_maps(db: Session = Depends(get_db)) -> List[dict]:
    """List all public buildings (summary only)."""
    buildings = db.query(Building).filter(Building.is_public.is_(True)).all()
    result = []
    for b in buildings:
        map_data = b.map_data or {}
        result.append(
            {
                "id": b.id,
                "name": b.name,
                "uploaded_by": b.uploaded_by,
                "upload_date": b.upload_date,
                "version": b.version,
                "node_count": len(map_data.get("nodes", [])),
                "edge_count": len(map_data.get("edges", [])),
            }
        )
    return result


# ── Get ────────────────────────────────────────────────────────────────────


@router.get(
    "/{building_id}",
    response_model=BuildingResponse,
    responses={404: {"model": ErrorResponse}},
)
def get_map(building_id: str, db: Session = Depends(get_db)) -> Building:
    """Download a specific building map."""
    return _get_building_or_404(building_id, db)


# ── Update ─────────────────────────────────────────────────────────────────


@router.put(
    "/{building_id}",
    response_model=BuildingResponse,
    responses={404: {"model": ErrorResponse}},
)
def update_map(
    building_id: str,
    payload: BuildingUpdate,
    db: Session = Depends(get_db),
) -> Building:
    """Update an existing building map."""
    building = _get_building_or_404(building_id, db)

    changes: dict = {}
    if payload.name is not None:
        building.name = payload.name
        changes["name"] = payload.name
    if payload.map_data is not None:
        building.map_data = payload.map_data.model_dump()
        changes["node_count"] = len(payload.map_data.nodes)
        changes["edge_count"] = len(payload.map_data.edges)
    if payload.is_public is not None:
        building.is_public = payload.is_public
        changes["is_public"] = payload.is_public

    building.version += 1

    db.add(
        MapUpdate(
            building_id=building_id,
            update_type="UPDATE",
            changes=changes,
        )
    )
    db.commit()
    db.refresh(building)
    return building


# ── Delete ─────────────────────────────────────────────────────────────────


@router.delete(
    "/{building_id}",
    status_code=204,
    responses={404: {"model": ErrorResponse}},
)
def delete_map(building_id: str, db: Session = Depends(get_db)) -> None:
    """Delete a building map."""
    building = _get_building_or_404(building_id, db)
    db.delete(building)
    db.add(
        MapUpdate(
            building_id=building_id,
            update_type="DELETE",
            timestamp=datetime.now(timezone.utc),
        )
    )
    db.commit()


# ── Merge ──────────────────────────────────────────────────────────────────


@router.post(
    "/merge",
    response_model=BuildingResponse,
    status_code=201,
    responses={404: {"model": ErrorResponse}},
)
def merge_building_maps(
    payload: MergeRequest,
    db: Session = Depends(get_db),
) -> Building:
    """Merge two building maps into a new combined map."""
    building_a = _get_building_or_404(payload.building_id_a, db)
    building_b = _get_building_or_404(payload.building_id_b, db)

    map_a = BuildingMapData(**building_a.map_data)
    map_b = BuildingMapData(**building_b.map_data)

    merged = merge_maps(map_a, map_b)
    merged_name = payload.merged_name or f"{building_a.name} + {building_b.name}"
    merged_id = f"merged_{building_a.id}_{building_b.id}"

    # Remove existing merged map if re-merging
    existing = db.query(Building).filter(Building.id == merged_id).first()
    if existing:
        db.delete(existing)
        db.flush()

    new_building = Building(
        id=merged_id,
        name=merged_name,
        uploaded_by="system-merge",
        map_data=merged.model_dump(),
        version=max(building_a.version, building_b.version) + 1,
        is_public=True,
    )
    db.add(new_building)
    db.add(
        MapUpdate(
            building_id=merged_id,
            update_type="MERGE",
            changes={
                "source_a": building_a.id,
                "source_b": building_b.id,
                "node_count": len(merged.nodes),
                "edge_count": len(merged.edges),
            },
        )
    )
    db.commit()
    db.refresh(new_building)
    return new_building
