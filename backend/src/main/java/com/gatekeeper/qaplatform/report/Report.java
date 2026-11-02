package com.gatekeeper.qaplatform.report;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report")
public class Report extends BaseEntity {

    private UUID runId;
    private String htmlKey;
    private String pdfKey;

    @JdbcTypeCode(SqlTypes.JSON)
    private String summaryJson;

    protected Report() {}

    public Report(UUID runId) {
        this.runId = runId;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getHtmlKey() {
        return htmlKey;
    }

    public void setHtmlKey(String htmlKey) {
        this.htmlKey = htmlKey;
    }

    public String getPdfKey() {
        return pdfKey;
    }

    public void setPdfKey(String pdfKey) {
        this.pdfKey = pdfKey;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }
}
