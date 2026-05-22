"""
Seed the database with a sample 7-node building map for testing.

Usage:  python seed_data.py
"""

import json
import sys

import httpx


BACKEND_URL = "http://127.0.0.1:8000"

SAMPLE_MAP = {
    "id": "building_cse_block",
    "name": "CSE Department Block",
    "uploaded_by": "volunteer_1",
    "is_public": True,
    "map_data": {
        "nodes": [
            {
                "id": "n1",
                "label": "Main Entrance",
                "floor": 0,
                "pose": {"x": 0.0, "y": 0.0, "z": 0.0},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Campus_WiFi", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -45},
                        {"ssid": "CSE_Lab", "bssid": "AA:BB:CC:DD:EE:02", "rssi": -70},
                    ],
                    "ble": [],
                },
                "type": "ENTRANCE",
                "timestamp": 1700000000000,
            },
            {
                "id": "n2",
                "label": "Ground Floor Corridor",
                "floor": 0,
                "pose": {"x": 10.0, "y": 0.0, "z": 0.0},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Campus_WiFi", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -50},
                        {"ssid": "CSE_Lab", "bssid": "AA:BB:CC:DD:EE:02", "rssi": -55},
                    ],
                    "ble": [],
                },
                "type": "INTERSECTION",
                "timestamp": 1700000005000,
            },
            {
                "id": "n3",
                "label": "Room 101 - Lab",
                "floor": 0,
                "pose": {"x": 10.0, "y": 8.0, "z": 0.0},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "CSE_Lab", "bssid": "AA:BB:CC:DD:EE:02", "rssi": -30},
                        {"ssid": "Campus_WiFi", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -60},
                    ],
                    "ble": [],
                },
                "type": "ROOM",
                "timestamp": 1700000010000,
            },
            {
                "id": "n4",
                "label": "Staircase A",
                "floor": 0,
                "pose": {"x": 20.0, "y": 0.0, "z": 0.0},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Campus_WiFi", "bssid": "AA:BB:CC:DD:EE:01", "rssi": -55},
                    ],
                    "ble": [],
                },
                "type": "STAIRS",
                "timestamp": 1700000015000,
            },
            {
                "id": "n5",
                "label": "First Floor Landing",
                "floor": 1,
                "pose": {"x": 20.0, "y": 0.0, "z": 3.5},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Campus_WiFi_5G", "bssid": "AA:BB:CC:DD:FF:01", "rssi": -40},
                    ],
                    "ble": [],
                },
                "type": "STAIRS",
                "timestamp": 1700000020000,
            },
            {
                "id": "n6",
                "label": "First Floor Corridor",
                "floor": 1,
                "pose": {"x": 10.0, "y": 0.0, "z": 3.5},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Campus_WiFi_5G", "bssid": "AA:BB:CC:DD:FF:01", "rssi": -45},
                        {"ssid": "Faculty_Net", "bssid": "AA:BB:CC:DD:FF:02", "rssi": -50},
                    ],
                    "ble": [],
                },
                "type": "INTERSECTION",
                "timestamp": 1700000025000,
            },
            {
                "id": "n7",
                "label": "Room 205",
                "floor": 1,
                "pose": {"x": 10.0, "y": 6.0, "z": 3.5},
                "fingerprints": {
                    "wifi": [
                        {"ssid": "Faculty_Net", "bssid": "AA:BB:CC:DD:FF:02", "rssi": -35},
                        {"ssid": "Campus_WiFi_5G", "bssid": "AA:BB:CC:DD:FF:01", "rssi": -55},
                    ],
                    "ble": [],
                },
                "type": "ROOM",
                "timestamp": 1700000030000,
            },
        ],
        "edges": [
            {
                "id": "e1",
                "from_node_id": "n1",
                "to_node_id": "n2",
                "length_meters": 10.0,
                "heading": 90.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            },
            {
                "id": "e2",
                "from_node_id": "n2",
                "to_node_id": "n3",
                "length_meters": 8.0,
                "heading": 0.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            },
            {
                "id": "e3",
                "from_node_id": "n2",
                "to_node_id": "n4",
                "length_meters": 10.0,
                "heading": 90.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            },
            {
                "id": "e4",
                "from_node_id": "n4",
                "to_node_id": "n5",
                "length_meters": 3.5,
                "heading": 0.0,
                "attributes": {
                    "has_stairs": True,
                    "stair_count": 18,
                    "stair_direction": "UP",
                    "is_accessible": False,
                },
            },
            {
                "id": "e5",
                "from_node_id": "n5",
                "to_node_id": "n6",
                "length_meters": 10.0,
                "heading": 270.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            },
            {
                "id": "e6",
                "from_node_id": "n6",
                "to_node_id": "n7",
                "length_meters": 6.0,
                "heading": 0.0,
                "attributes": {"has_stairs": False, "is_accessible": True},
            },
        ],
    },
}


def seed() -> None:
    """Upload the sample building map to the running backend."""
    resp = httpx.post(f"{BACKEND_URL}/api/maps/upload", json=SAMPLE_MAP, timeout=10)
    if resp.status_code == 201:
        print("✅ Sample map uploaded successfully!")
        data = resp.json()
        print(f"   Building: {data['name']}")
        nodes = data["map_data"]["nodes"]
        edges = data["map_data"]["edges"]
        print(f"   Nodes: {len(nodes)}  |  Edges: {len(edges)}")
    elif resp.status_code == 409:
        print("ℹ️  Sample map already exists.")
    else:
        print(f"❌ Upload failed ({resp.status_code}): {resp.text}")
        sys.exit(1)


if __name__ == "__main__":
    seed()
