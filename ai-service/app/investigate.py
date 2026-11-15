from fastapi import APIRouter

from app.contracts import InvestigateAck, InvestigationRequest
from app.request_store import accept_request, ensure_schema

router = APIRouter()


@router.post("/investigate", status_code=202, response_model=InvestigateAck)
def investigate(request: InvestigationRequest) -> InvestigateAck:
    # Imported here so app.main can load this router without a circular import.
    from app.main import connect

    with connect() as conn:
        ensure_schema(conn)
    with connect() as conn:
        status = accept_request(conn, request)
    return InvestigateAck(case_id=request.case_id, status=status)
