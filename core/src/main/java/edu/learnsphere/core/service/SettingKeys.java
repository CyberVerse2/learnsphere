package edu.learnsphere.core.service;

import edu.learnsphere.core.spi.ModelChoice;
import java.util.List;

/** Every setting LearnSphere stores per profile, with its default. */
public final class SettingKeys {

    /** Light, dark or follow the system. Default: system. */
    public static final SettingKey<ThemePreference> THEME = new SettingKey<>("theme", ThemePreference.class,
            ThemePreference.SYSTEM, ThemePreference::fromCode, ThemePreference::code, v -> true);

    /** New items introduced per day across courses. Default 15, range 0–200. */
    public static final SettingKey<Integer> NEW_ITEMS_PER_DAY = new SettingKey<>("practice.new_items_per_day", Integer.class,
            15, Integer::valueOf, String::valueOf, v -> v >= 0 && v <= 200);

    /** Target probability of recalling an item when it is due. Default 0.90, range 0.70–0.97. */
    public static final SettingKey<Double> DESIRED_RETENTION = new SettingKey<>("practice.desired_retention", Double.class,
            0.90, Double::valueOf, String::valueOf, v -> v >= 0.70 && v <= 0.97);

    /** Items in a study session. Default 20, range 5–100. */
    public static final SettingKey<Integer> SESSION_SIZE = new SettingKey<>("practice.session_size", Integer.class,
            20, Integer::valueOf, String::valueOf, v -> v >= 5 && v <= 100);

    /** Describe figures in imported PDFs. Default on. */
    public static final SettingKey<Boolean> DESCRIBE_FIGURES = new SettingKey<>("import.describe_figures", Boolean.class,
            true, Boolean::valueOf, String::valueOf, v -> true);

    /** Chat model. Default: chosen by hardware. */
    public static final SettingKey<ModelChoice> MODEL_CHOICE = new SettingKey<>("assistant.model", ModelChoice.class,
            ModelChoice.AUTO, ModelChoice::fromCode, ModelChoice::code, v -> true);

    /** Lowest reranker score a passage needs to count as evidence. Default 0.35 (tuned by the testbench). */
    public static final SettingKey<Double> EVIDENCE_THRESHOLD = new SettingKey<>("evidence.threshold", Double.class,
            0.35, Double::valueOf, String::valueOf, v -> v >= 0 && v <= 1);

    /** Replace movement with fades. Default off. */
    public static final SettingKey<Boolean> REDUCE_MOTION = new SettingKey<>("ui.reduce_motion", Boolean.class,
            false, Boolean::valueOf, String::valueOf, v -> true);

    /** Onboarding finished. Default false. */
    public static final SettingKey<Boolean> ONBOARDING_COMPLETED = new SettingKey<>("onboarding.completed", Boolean.class,
            false, Boolean::valueOf, String::valueOf, v -> true);

    /** Every key. */
    public static final List<SettingKey<?>> ALL = List.of(THEME, NEW_ITEMS_PER_DAY, DESIRED_RETENTION, SESSION_SIZE,
            DESCRIBE_FIGURES, MODEL_CHOICE, EVIDENCE_THRESHOLD, REDUCE_MOTION, ONBOARDING_COMPLETED);

    private SettingKeys() {
    }
}
