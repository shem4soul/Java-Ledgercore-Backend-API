package com.ledgercore.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds RFC 7807 problem documents with a stable "code" property. */
public final class ProblemFactory {
    private ProblemFactory() {}

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        return pd;
    }
}
