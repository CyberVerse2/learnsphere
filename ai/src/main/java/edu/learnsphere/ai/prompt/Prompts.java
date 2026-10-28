package edu.learnsphere.ai.prompt;

/**
 * Every prompt the AI module sends, loaded once from the resource files in this package. Edit the
 * {@code .txt} files to change wording; {@code ./gradlew :ai:integrationTest} measures the effect against
 * the real models.
 */
public final class Prompts {

    /**
     * What the answerer replies when the passages do not contain the answer. Never shown to users:
     * {@code LlamaGroundedAnswerer} turns it into an insufficient-evidence result.
     */
    public static final String NOT_IN_MATERIAL = "NOT_IN_MATERIAL";

    /** Grounded answers: rules for citing numbered passages and refusing with {@link #NOT_IN_MATERIAL}. */
    public static final PromptTemplate ANSWER_SYSTEM = PromptTemplate.load("answer-system.txt");
    /** Grounded answers: {@code {{passages}}}, {@code {{question}}}. */
    public static final PromptTemplate ANSWER_USER = PromptTemplate.load("answer-user.txt");
    /** Grounded answers, worked example: passages shared by both example questions. */
    public static final PromptTemplate ANSWER_EXAMPLE_PASSAGES = PromptTemplate.load("answer-example-passages.txt");
    /** Grounded answers, worked example: a question the example passages answer. */
    public static final PromptTemplate ANSWER_EXAMPLE_QUESTION = PromptTemplate.load("answer-example-question.txt");
    /** Grounded answers, worked example: the cited answer to {@link #ANSWER_EXAMPLE_QUESTION}. */
    public static final PromptTemplate ANSWER_EXAMPLE_ANSWER = PromptTemplate.load("answer-example-answer.txt");
    /** Grounded answers, worked example: a related question the passages don't answer (reply: the sentinel). */
    public static final PromptTemplate ANSWER_EXAMPLE_REFUSAL = PromptTemplate.load("answer-example-refusal.txt");

    /** Concept extraction: {@code {{min}}}, {@code {{max}}}. */
    public static final PromptTemplate CONCEPTS_SYSTEM = PromptTemplate.load("concepts-system.txt");
    /** Concept extraction: {@code {{module}}}, {@code {{passages}}}. */
    public static final PromptTemplate CONCEPTS_USER = PromptTemplate.load("concepts-user.txt");

    /** Item generation: quality rules per item type. */
    public static final PromptTemplate ITEMS_SYSTEM = PromptTemplate.load("items-system.txt");
    /** Item generation: {@code {{passages}}}, {@code {{concepts}}}, {@code {{avoid}}}, {@code {{plan}}}. */
    public static final PromptTemplate ITEMS_USER = PromptTemplate.load("items-user.txt");

    /** Question reading: greeting, vague or question, and how to rewrite a follow-up. */
    public static final PromptTemplate READ_SYSTEM = PromptTemplate.load("read-system.txt");
    /** Question reading: {@code {{earlier}}}, {@code {{message}}}. */
    public static final PromptTemplate READ_USER = PromptTemplate.load("read-user.txt");

    /** Support check: what counts as supported. */
    public static final PromptTemplate SUPPORT_SYSTEM = PromptTemplate.load("support-system.txt");
    /** Support check: {@code {{source}}}, {@code {{passage}}}, {@code {{type}}}, {@code {{prompt}}}, {@code {{answer}}}. */
    public static final PromptTemplate SUPPORT_USER = PromptTemplate.load("support-user.txt");
    public static final PromptTemplate STATEMENT_SYSTEM = PromptTemplate.load("statement-system.txt");
    public static final PromptTemplate STATEMENT_USER = PromptTemplate.load("statement-user.txt");

    /** Short-answer grading: how to judge key points fairly. */
    public static final PromptTemplate GRADE_SYSTEM = PromptTemplate.load("grade-system.txt");
    /** Short-answer grading: {@code {{question}}}, {@code {{reference}}}, {@code {{context}}}, {@code {{answer}}}. */
    public static final PromptTemplate GRADE_USER = PromptTemplate.load("grade-user.txt");

    /** Page reading: transcription rules. */
    public static final PromptTemplate PAGE_SYSTEM = PromptTemplate.load("page-system.txt");
    /** Page reading: {@code {{page}}}. */
    public static final PromptTemplate PAGE_USER = PromptTemplate.load("page-user.txt");

    /** Figure description: what counts as a figure and how to describe it. */
    public static final PromptTemplate FIGURES_SYSTEM = PromptTemplate.load("figures-system.txt");
    /** Figure description: {@code {{page}}}. */
    public static final PromptTemplate FIGURES_USER = PromptTemplate.load("figures-user.txt");

    private Prompts() {
    }
}
