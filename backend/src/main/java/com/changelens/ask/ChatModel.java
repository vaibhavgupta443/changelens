package com.changelens.ask;

/** A language model, reduced to what answering a question needs: one request in, one text out. */
public interface ChatModel {

    /**
     * @param system what the model must do and may not do
     * @param user   the question and the evidence
     * @return the model's reply text
     * @throws ModelException if the model could not be reached or refused the request
     */
    String complete(String system, String user);

    /** A name for logs and answers, such as the model id. */
    default String name() {
        return "model";
    }

    /** The model could not be used: no connection, a rejected key, a quota, or a reply that was not a completion. */
    final class ModelException extends RuntimeException {
        public ModelException(String message) {
            super(message);
        }

        public ModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
