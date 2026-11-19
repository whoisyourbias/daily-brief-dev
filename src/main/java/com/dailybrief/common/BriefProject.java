package com.dailybrief.common;

public enum BriefProject {
    REACT("React", "facebook/react"),
    NEXT_JS("Next.js", "vercel/next.js"),
    SPRING_BOOT("Spring Boot", "spring-projects/spring-boot");

    private final String displayName;
    private final String repository;

    BriefProject(String displayName, String repository) {
        this.displayName = displayName;
        this.repository = repository;
    }

    public String displayName() {
        return displayName;
    }

    public String repository() {
        return repository;
    }
}
