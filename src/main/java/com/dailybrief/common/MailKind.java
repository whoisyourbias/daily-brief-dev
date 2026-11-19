package com.dailybrief.common;

public enum MailKind {
    NEWS("Daily Brief · 오늘의 뉴스"),
    TECHNOLOGY("Daily Brief · 내 기술 업데이트");

    private final String subject;

    MailKind(String subject) {
        this.subject = subject;
    }

    public String subject() {
        return subject;
    }
}
