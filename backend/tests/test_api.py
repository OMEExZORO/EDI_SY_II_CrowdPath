"""
Backend API test suite.

Run with:  pytest tests/ -v
"""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from database import Base, get_db
from main import app

# ── Test DB setup (in-memory SQLite) ───────────────────────────────────────

TEST_DATABASE_URL = "sqlite:///./test_maps.db"
test_engine = create_engine(TEST_DATABASE_URL, connect_args={"check_same_thread": False})
TestSession = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


def override_get_db():
    db = TestSession()
    try:
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = override_get_db

client = TestClient(app)


@pytest.fixture(autouse=True)
def setup_db():
    """Recreate all tables before each test."""
    Base.metadata.create_all(bind=test_engine)
    yield
    Base.metadata.drop_all(bind=test_engine)


# ── Sample payloads ───────────────────────────────────────────────────────

VALID_MAP = {
    "id": "test_building",
    "name": "Test Building",
    "uploaded_by": "tester",
    "map_data": {
        "nodes": [
            {
                "id": "a",
                "label": "Entrance",
                "floor": 0,
                "pose": {"x": 0, "y": 0, "z": 0},
                "type": "ENTRANCE",
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Net1", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -50}
                    ],
                    "ble": [],
                },
            },
            {
                "id": "b",
                "label": "Room 1",
                "floor": 0,
                "pose": {"x": 10, "y": 0, "z": 0},
                "type": "ROOM",
                "fingerprints": {"wifi": [], "ble": []},
            },
        ],
        "edges": [
            {
                "id": "e1",
                "from_node_id": "a",
                "to_node_id": "b",
                "length_meters": 10.0,
                "heading": 90.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            }
        ],
    },
}


# ── Tests ──────────────────────────────────────────────────────────────────


class TestHealthCheck:
    def test_root(self):
        r = client.get("/")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"


class TestUpload:
    def test_upload_success(self):
        r = client.post("/api/maps/upload", json=VALID_MAP)
        assert r.status_code == 201
        data = r.json()
        assert data["id"] == "test_building"
        assert data["version"] == 1
        assert len(data["map_data"]["nodes"]) == 2

    def test_upload_duplicate(self):
        client.post("/api/maps/upload", json=VALID_MAP)
        r = client.post("/api/maps/upload", json=VALID_MAP)
        assert r.status_code == 409

    def test_upload_invalid_graph_orphan_node(self):
        bad = {
            **VALID_MAP,
            "id": "bad_building",
            "map_data": {
                "nodes": [
                    {"id": "a", "label": "N1", "pose": {"x": 0, "y": 0, "z": 0}, "type": "ROOM"},
                    {"id": "b", "label": "N2", "pose": {"x": 1, "y": 0, "z": 0}, "type": "ROOM"},
                    {"id": "c", "label": "Orphan", "pose": {"x": 9, "y": 9, "z": 0}, "type": "ROOM"},
                ],
                "edges": [
                    {
                        "id": "e1",
                        "from_node_id": "a",
                        "to_node_id": "b",
                        "length_meters": 1.0,
                        "heading": 90.0,
                    }
                ],
            },
        }
        r = client.post("/api/maps/upload", json=bad)
        assert r.status_code == 422  # Pydantic validation error

    def test_upload_invalid_edge_length(self):
        bad = {
            **VALID_MAP,
            "id": "bad_edge",
            "map_data": {
                "nodes": [
                    {"id": "a", "label": "N1", "pose": {"x": 0, "y": 0, "z": 0}, "type": "ROOM"},
                    {"id": "b", "label": "N2", "pose": {"x": 1, "y": 0, "z": 0}, "type": "ROOM"},
                ],
                "edges": [
                    {
                        "id": "e1",
                        "from_node_id": "a",
                        "to_node_id": "b",
                        "length_meters": 999.0,  # > 200m
                        "heading": 90.0,
                    }
                ],
            },
        }
        r = client.post("/api/maps/upload", json=bad)
        assert r.status_code == 422


class TestList:
    def test_list_empty(self):
        r = client.get("/api/maps/list")
        assert r.status_code == 200
        assert r.json() == []

    def test_list_after_upload(self):
        client.post("/api/maps/upload", json=VALID_MAP)
        r = client.get("/api/maps/list")
        assert r.status_code == 200
        items = r.json()
        assert len(items) == 1
        assert items[0]["node_count"] == 2


class TestGet:
    def test_get_success(self):
        client.post("/api/maps/upload", json=VALID_MAP)
        r = client.get("/api/maps/test_building")
        assert r.status_code == 200
        assert r.json()["name"] == "Test Building"

    def test_get_not_found(self):
        r = client.get("/api/maps/nonexistent")
        assert r.status_code == 404


class TestUpdate:
    def test_update_name(self):
        client.post("/api/maps/upload", json=VALID_MAP)
        r = client.put("/api/maps/test_building", json={"name": "Renamed"})
        assert r.status_code == 200
        assert r.json()["name"] == "Renamed"
        assert r.json()["version"] == 2

    def test_update_not_found(self):
        r = client.put("/api/maps/nonexistent", json={"name": "X"})
        assert r.status_code == 404


class TestDelete:
    def test_delete_success(self):
        client.post("/api/maps/upload", json=VALID_MAP)
        r = client.delete("/api/maps/test_building")
        assert r.status_code == 204

        r = client.get("/api/maps/test_building")
        assert r.status_code == 404

    def test_delete_not_found(self):
        r = client.delete("/api/maps/nonexistent")
        assert r.status_code == 404


class TestMerge:
    def _upload_two(self):
        map_a = {**VALID_MAP, "id": "bld_a", "name": "Building A"}
        map_b = {
            "id": "bld_b",
            "name": "Building B",
            "uploaded_by": "tester",
            "map_data": {
                "nodes": [
                    {
                        "id": "a",
                        "label": "Entrance",
                        "floor": 0,
                        "pose": {"x": 0.5, "y": 0.3, "z": 0},  # close to bld_a node "a"
                        "type": "ENTRANCE",
                        "fingerprints": {
                            "wifi": [
                                {"ssid": "Net1", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -40}
                            ],
                            "ble": [],
                        },
                    },
                    {
                        "id": "c",
                        "label": "Room 2",
                        "floor": 0,
                        "pose": {"x": 10, "y": 5, "z": 0},
                        "type": "ROOM",
                        "fingerprints": {"wifi": [], "ble": []},
                    },
                ],
                "edges": [
                    {
                        "id": "e2",
                        "from_node_id": "a",
                        "to_node_id": "c",
                        "length_meters": 11.0,
                        "heading": 60.0,
                    }
                ],
            },
        }
        client.post("/api/maps/upload", json=map_a)
        client.post("/api/maps/upload", json=map_b)

    def test_merge_success(self):
        self._upload_two()
        r = client.post(
            "/api/maps/merge",
            json={"building_id_a": "bld_a", "building_id_b": "bld_b"},
        )
        assert r.status_code == 201
        data = r.json()
        # Should have at least 3 unique nodes (a merged, b, c)
        assert len(data["map_data"]["nodes"]) >= 3
        assert len(data["map_data"]["edges"]) >= 2

    def test_merge_not_found(self):
        r = client.post(
            "/api/maps/merge",
            json={"building_id_a": "x", "building_id_b": "y"},
        )
        assert r.status_code == 404
