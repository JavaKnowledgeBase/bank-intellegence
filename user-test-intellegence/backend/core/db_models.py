from sqlalchemy import Column, Integer, MetaData, String, Text
from sqlalchemy.orm import declarative_base


metadata = MetaData()
Base = declarative_base(metadata=metadata)


class TestCaseRow(Base):
    __tablename__ = "test_cases"
    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    scenario_description = Column(Text, nullable=False)
    target_service = Column(String, nullable=False)
    endpoint = Column(String, nullable=False)
    priority = Column(String, nullable=False)
    status = Column(String, nullable=False)
    source = Column(String, nullable=False)
    tags = Column(Text, nullable=False)
    steps = Column(Text, nullable=False)
    last_run_status = Column(String, nullable=False)
    created_at = Column(String, nullable=False)
    updated_at = Column(String, nullable=False)


class SuiteRunRow(Base):
    __tablename__ = "suite_runs"
    id = Column(String, primary_key=True)
    service = Column(String, nullable=False)
    status = Column(String, nullable=False)
    trigger_type = Column(String, nullable=False)
    trigger_actor = Column(String, nullable=False)
    commit_sha = Column(String)
    branch = Column(String)
    total_tests = Column(Integer, nullable=False)
    total_shards = Column(Integer, nullable=False)
    completed_shards = Column(Integer, nullable=False)
    passed = Column(Integer, nullable=False)
    failed = Column(Integer, nullable=False)
    errored = Column(Integer, nullable=False)
    duration_ms = Column(Integer, nullable=False)
    started_at = Column(String, nullable=False)
    completed_at = Column(String)


class ShardRunRow(Base):
    __tablename__ = "shard_runs"
    suite_run_id = Column(String, primary_key=True)
    shard_number = Column(Integer, primary_key=True)
    status = Column(String, nullable=False)
    passed = Column(Integer, nullable=False)
    failed = Column(Integer, nullable=False)
    errored = Column(Integer, nullable=False)
    duration_ms = Column(Integer, nullable=False)
    test_case_ids = Column(Text, nullable=False)
    failure_messages = Column(Text, nullable=False)


class ScoutObservationRow(Base):
    __tablename__ = "scout_observations"
    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    source_url = Column(Text, nullable=False)
    domain = Column(String, nullable=False)
    summary = Column(Text, nullable=False)
    safety_status = Column(String, nullable=False)
    proposed_service = Column(String, nullable=False)
    tags = Column(Text, nullable=False)
    discovered_at = Column(String, nullable=False)


class ImprovementProposalRow(Base):
    __tablename__ = "improvement_proposals"
    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    area = Column(String, nullable=False)
    summary = Column(Text, nullable=False)
    expected_impact = Column(Text, nullable=False)
    status = Column(String, nullable=False)
    created_at = Column(String, nullable=False)


class ExecutionEventRow(Base):
    __tablename__ = "execution_events"
    id = Column(Integer, primary_key=True, autoincrement=True)
    suite_run_id = Column(String, nullable=False)
    service = Column(String, nullable=False)
    event_type = Column(String, nullable=False)
    level = Column(String, nullable=False)
    message = Column(Text, nullable=False)
    event_payload = Column(Text, nullable=False)
    created_at = Column(String, nullable=False)
