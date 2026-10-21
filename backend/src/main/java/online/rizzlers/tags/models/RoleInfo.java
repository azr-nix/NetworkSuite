package online.rizzlers.tags.models;

public record RoleInfo(
        String key,
        String tag,
        String color,
        int priority
) implements Comparable<RoleInfo> {
    @Override
    public int compareTo(RoleInfo other) {
        // Higher priority sorts first
        return Integer.compare(other.priority, this.priority);
    }
}
