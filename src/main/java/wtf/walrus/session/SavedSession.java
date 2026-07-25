package wtf.walrus.session;

import wtf.walrus.config.Label;
import wtf.walrus.data.DataType;

import java.util.UUID;

public record SavedSession(UUID uuid, Label label, String comment, DataType type) {
}
