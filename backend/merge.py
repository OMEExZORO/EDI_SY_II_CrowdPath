"""
Map merging logic — combine two building maps by matching nearby nodes.
"""

import math
from typing import Dict, List, Tuple

from schemas import (
    BuildingMapData,
    EdgeSchema,
    Fingerprints,
    NodeSchema,
    WiFiReading,
)


def _distance(a: NodeSchema, b: NodeSchema) -> float:
    """Euclidean distance between two nodes in 3-D (meters)."""
    return math.sqrt(
        (a.pose.x - b.pose.x) ** 2
        + (a.pose.y - b.pose.y) ** 2
        + (a.pose.z - b.pose.z) ** 2
    )


def _average_wifi(
    fp_a: List[WiFiReading], fp_b: List[WiFiReading]
) -> List[WiFiReading]:
    """Average RSSI for WiFi readings sharing the same BSSID."""
    bssid_map: Dict[str, Tuple[str, List[int]]] = {}

    for r in fp_a + fp_b:
        if r.bssid in bssid_map:
            bssid_map[r.bssid][1].append(r.rssi)
        else:
            bssid_map[r.bssid] = (r.ssid, [r.rssi])

    merged: List[WiFiReading] = []
    for bssid, (ssid, rssi_list) in bssid_map.items():
        avg_rssi = int(sum(rssi_list) / len(rssi_list))
        merged.append(WiFiReading(ssid=ssid, bssid=bssid, rssi=avg_rssi))

    return merged


def _merge_node(a: NodeSchema, b: NodeSchema) -> NodeSchema:
    """Merge two nodes that represent the same physical location."""
    return NodeSchema(
        id=a.id,  # keep A's id
        label=a.label or b.label,
        floor=a.floor,
        pose=a.pose,  # keep A's pose (first mapper)
        fingerprints=Fingerprints(
            wifi=_average_wifi(a.fingerprints.wifi, b.fingerprints.wifi),
            ble=a.fingerprints.ble + b.fingerprints.ble,
        ),
        type=a.type,
        timestamp=a.timestamp,
    )


def merge_maps(
    map_a: BuildingMapData,
    map_b: BuildingMapData,
    proximity_threshold_m: float = 2.0,
) -> BuildingMapData:
    """
    Merge two building maps by matching nearby nodes.

    Strategy:
    - Match nodes from B to A if within `proximity_threshold_m`.
    - Matched nodes get averaged WiFi fingerprints.
    - Unmatched nodes from B are added with remapped IDs.
    - All edges are kept (union), with IDs remapped for B-only nodes.
    """
    # 1. Match nodes ---------------------------------------------------------
    id_remap: Dict[str, str] = {}  # B-node-id → A-node-id (for matched)
    merged_nodes: Dict[str, NodeSchema] = {n.id: n for n in map_a.nodes}

    for node_b in map_b.nodes:
        best_match: NodeSchema | None = None
        best_dist = proximity_threshold_m

        for node_a in map_a.nodes:
            d = _distance(node_a, node_b)
            if d < best_dist:
                best_dist = d
                best_match = node_a

        if best_match is not None:
            # Matched — merge fingerprints
            id_remap[node_b.id] = best_match.id
            merged_nodes[best_match.id] = _merge_node(best_match, node_b)
        else:
            # Unmatched — add as new node (prefix to avoid collision)
            new_id = f"B_{node_b.id}" if node_b.id in merged_nodes else node_b.id
            id_remap[node_b.id] = new_id
            merged_nodes[new_id] = node_b.model_copy(update={"id": new_id})

    # 2. Merge edges (union) -------------------------------------------------
    merged_edge_keys: set = set()
    merged_edges: List[EdgeSchema] = []

    for edge in map_a.edges:
        key = (edge.from_node_id, edge.to_node_id)
        if key not in merged_edge_keys:
            merged_edge_keys.add(key)
            merged_edges.append(edge)

    for edge in map_b.edges:
        from_id = id_remap.get(edge.from_node_id, edge.from_node_id)
        to_id = id_remap.get(edge.to_node_id, edge.to_node_id)
        key = (from_id, to_id)
        reverse_key = (to_id, from_id)

        if key not in merged_edge_keys and reverse_key not in merged_edge_keys:
            new_edge_id = f"B_{edge.id}" if edge.id in {e.id for e in merged_edges} else edge.id
            merged_edge_keys.add(key)
            merged_edges.append(
                edge.model_copy(
                    update={
                        "id": new_edge_id,
                        "from_node_id": from_id,
                        "to_node_id": to_id,
                    }
                )
            )

    return BuildingMapData(
        nodes=list(merged_nodes.values()),
        edges=merged_edges,
    )
