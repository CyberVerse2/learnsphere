package edu.learnsphere.core.model;

/** State of a background job. Stored as its lowercase {@link #code() code}. */
public enum JobStatus implements CodedEnum {

    /** Waiting to run. */
    QUEUED,

    /** Running now. */
    RUNNING,

    /** Finished successfully. */
    DONE,

    /** Stopped with an error. */
    FAILED,

    /** Stopped by the user. */
    CANCELLED;

    private final String code = CodedEnum.codeOf(this);

    @Override
    public String code() {
        return code;
    }

    /**
     * The constant stored as {@code code}.
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static JobStatus fromCode(String code) {
        return CodedEnum.fromCode(JobStatus.class, code);
    }
}
