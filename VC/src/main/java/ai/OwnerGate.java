package ai;

/**
 * Installation owner check. The JWT subject is a user id; only an owner may store a provider key.
 */
public interface OwnerGate {

    boolean isOwner(String userId);
}
