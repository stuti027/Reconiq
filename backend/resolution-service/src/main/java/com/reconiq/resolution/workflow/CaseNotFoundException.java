package com.reconiq.resolution.workflow;

public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String caseId) {
        super("Case " + caseId + " does not exist");
    }
}
