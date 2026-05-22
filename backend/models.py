"""
SQLAlchemy ORM models for the Smart Cane backend.
"""

from datetime import datetime, timezone

from sqlalchemy import Boolean, Column, DateTime, Integer, String, Text
from sqlalchemy.types import JSON

from database import Base


class Building(Base):
    """Stores a building's navigation map (nodes + edges as JSON)."""

    __tablename__ = "buildings"

    id: str = Column(String(255), primary_key=True, index=True)
    name: str = Column(String(255), nullable=False)
    uploaded_by: str = Column(String(255), nullable=False)
    upload_date: datetime = Column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )
    map_data: dict = Column(JSON, nullable=False)  # {"nodes": [...], "edges": [...]}
    version: int = Column(Integer, default=1)
    is_public: bool = Column(Boolean, default=True)


class MapUpdate(Base):
    """Audit log for changes made to a building map."""

    __tablename__ = "map_updates"

    id: int = Column(Integer, primary_key=True, autoincrement=True)
    building_id: str = Column(String(255), nullable=False, index=True)
    update_type: str = Column(String(50), nullable=False)  # CREATE, UPDATE, MERGE
    timestamp: datetime = Column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )
    changes: dict = Column(JSON, nullable=True)
