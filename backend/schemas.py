"""
Pydantic v2 schemas for request / response validation.
"""

from datetime import datetime
from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, model_validator


# ── Enums ──────────────────────────────────────────────────────────────────

class NodeType(str, Enum):
    ROOM = "ROOM"
    INTERSECTION = "INTERSECTION"
    STAIRS = "STAIRS"
    ELEVATOR = "ELEVATOR"
    DOOR = "DOOR"
    ENTRANCE = "ENTRANCE"


class StairDirection(str, Enum):
    UP = "UP"
    DOWN = "DOWN"


# ── Leaf schemas ───────────────────────────────────────────────────────────

class Pose3D(BaseModel):
    x: float
    y: float
    z: float


class WiFiReading(BaseModel):
    ssid: str = ""
    bssid: str  # MAC address — most important
    rssi: int  # signal strength in dBm


class BLEReading(BaseModel):
    device_name: Optional[str] = None
    mac_address: str
    rssi: int


class Fingerprints(BaseModel):
    wifi: List[WiFiReading] = Field(default_factory=list)
    ble: List[BLEReading] = Field(default_factory=list)


class EdgeAttributes(BaseModel):
    has_stairs: bool = False
    stair_count: Optional[int] = None
    stair_direction: Optional[StairDirection] = None
    is_accessible: bool = True


# ── Node & Edge ────────────────────────────────────────────────────────────

class NodeSchema(BaseModel):
    id: str
    label: str
    floor: int = 0
    pose: Pose3D
    fingerprints: Fingerprints = Field(default_factory=Fingerprints)
    type: NodeType = NodeType.ROOM
    timestamp: Optional[int] = None  # epoch millis


class EdgeSchema(BaseModel):
    id: str
    from_node_id: str
    to_node_id: str
    length_meters: float = Field(gt=0, le=200)
    heading: float = Field(ge=0, lt=360)
    attributes: EdgeAttributes = Field(default_factory=EdgeAttributes)


# ── BuildingMap (the payload stored as JSON) ───────────────────────────────

class BuildingMapData(BaseModel):
    nodes: List[NodeSchema]
    edges: List[EdgeSchema]

    @model_validator(mode="after")
    def validate_graph(self) -> "BuildingMapData":
        """Every edge must reference existing nodes; no orphan nodes."""
        node_ids = {n.id for n in self.nodes}
        for edge in self.edges:
            if edge.from_node_id not in node_ids:
                raise ValueError(
                    f"Edge '{edge.id}' references unknown from_node_id "
                    f"'{edge.from_node_id}'"
                )
            if edge.to_node_id not in node_ids:
                raise ValueError(
                    f"Edge '{edge.id}' references unknown to_node_id "
                    f"'{edge.to_node_id}'"
                )
        # Check for orphan nodes (nodes not referenced by any edge)
        connected = set()
        for e in self.edges:
            connected.add(e.from_node_id)
            connected.add(e.to_node_id)
        orphans = node_ids - connected
        if orphans and len(self.nodes) > 1:
            raise ValueError(f"Orphan nodes detected: {orphans}")
        return self


# ── Request / Response models ──────────────────────────────────────────────

class BuildingCreate(BaseModel):
    id: str
    name: str
    uploaded_by: str = "anonymous"
    map_data: BuildingMapData
    is_public: bool = True


class BuildingUpdate(BaseModel):
    name: Optional[str] = None
    map_data: Optional[BuildingMapData] = None
    is_public: Optional[bool] = None


class BuildingResponse(BaseModel):
    id: str
    name: str
    uploaded_by: str
    upload_date: datetime
    map_data: BuildingMapData
    version: int
    is_public: bool

    class Config:
        from_attributes = True


class BuildingListItem(BaseModel):
    id: str
    name: str
    uploaded_by: str
    upload_date: datetime
    version: int
    node_count: int = 0
    edge_count: int = 0

    class Config:
        from_attributes = True


class MergeRequest(BaseModel):
    building_id_a: str
    building_id_b: str
    merged_name: Optional[str] = None


class ErrorResponse(BaseModel):
    detail: str
