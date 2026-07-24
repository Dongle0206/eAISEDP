package com.eaiselp.common.exception;

/** 不可逆操作人工锁异常（reliability-governance 防线1）。 */
public class HumanConfirmRequiredException extends RuntimeException {
    private final String operation;
    private final String caseId;
    public HumanConfirmRequiredException(String operation, String caseId) {
        super("不可逆操作需人工确认: operation=" + operation + ", caseId=" + caseId);
        this.operation = operation;
        this.caseId = caseId;
    }
    public String getOperation() { return operation; }
    public String getCaseId() { return caseId; }
}
