package wtf.walrus.alert;

import java.util.UUID;

public record FlagAction(String name, UUID uuid, double prob) {
}
