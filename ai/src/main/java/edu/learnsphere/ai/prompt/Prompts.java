package edu.learnsphere.ai.prompt;

/**
 * Every prompt the AI module sends, loaded once from the resource files in this package. Edit the
 * {@code .txt} files to change wording; {@code ./gradlew :ai:integrationTest} measures the effect against
 * the real models.
 */
public final class Prompts {

    /** Chat replies: how to talk, cite numbered passages, say what the course doesn't cover and quiz. */
    public static final PromptTemplate ANSWER_SYSTEM = PromptTemplate.load("answer-system.txt");
    /** Chat replies: {@code {{course}}}, {@code {{topics}}}, {@code {{passages}}}, {@code {{message}}}. */
    public static final PromptTemplate ANSWER_USER = PromptTemplate.load("answer-user.txt");
    /** Chat replies, worked examples: passages shared by every example message. */
    public static final PromptTemplate ANSWER_EXAMPLE_PASSAGES = PromptTemplate.load("answer-example-passages.txt");
    /** Chat replies, worked example: a question the example passages answer. */
    public static final PromptTemplate ANSWER_EXAMPLE_QUESTION = PromptTemplate.load("answer-example-question.txt");
    /** Chat replies, worked example: the cited answer to {@link #ANSWER_EXAMPLE_QUESTION}. */
    public static final PromptTemplate ANSWER_EXAMPLE_ANSWER = PromptTemplate.load("answer-example-answer.txt");
    /** Chat replies, worked example: a related question the passages don't answer. */
    public static final PromptTemplate ANSWER_EXAMPLE_UNCOVERED_QUESTION =
            PromptTemplate.load("answer-example-uncovered-question.txt");
    /** Chat replies, worked example: saying the course doesn't cover {@link #ANSWER_EXAMPLE_UNCOVERED_QUESTION}. */
    public static final PromptTemplate ANSWER_EXAMPLE_UNCOVERED = PromptTemplate.load("answer-example-uncovered.txt");
    /** Chat replies, worked example: one quiz question in reply to "quiz me". */
    public static final PromptTemplate ANSWER_EXAMPLE_QUIZ = PromptTemplate.load("answer-example-quiz.txt");
    /** Chat replies, worked example: a wrong answer to {@link #ANSWER_EXAMPLE_QUIZ}. */
    public static final PromptTemplate ANSWER_EXAMPLE_QUIZ_REPLY = PromptTemplate.load("answer-example-quiz-reply.txt");
    /** Chat replies, worked example: marking {@link #ANSWER_EXAMPLE_QUIZ_REPLY}. */
    public static final PromptTemplate ANSWER_EXAMPLE_QUIZ_MARKED = PromptTemplate.load("answer-example-quiz-marked.txt");

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

    /** Module suggestion: {@code {{max}}}. */
    public static final PromptTemplate MODULES_SYSTEM = PromptTemplate.load("modules-system.txt");
    /** Module suggestion: {@code {{course}}}, {@code {{sections}}}. */
    public static final PromptTemplate MODULES_USER = PromptTemplate.load("modules-user.txt");

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
