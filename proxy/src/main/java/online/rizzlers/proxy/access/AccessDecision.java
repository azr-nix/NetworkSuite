package online.rizzlers.proxy.access;

public record AccessDecision(
        boolean allowed,
        String resultType,
        String kickMessageTemplate,
        String internalReason
) {
    public static AccessDecision allow(String reason) {
        return new AccessDecision(true, "ALLOWED", null, reason);
    }

    public static AccessDecision deny(String resultType, String kickMessageTemplate, String internalReason) {
        return new AccessDecision(false, resultType, kickMessageTemplate, internalReason);
    }
}
