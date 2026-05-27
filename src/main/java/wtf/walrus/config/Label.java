package wtf.walrus.config;

public enum Label {

    CHEAT(1),
    LEGIT(0),
    UNLABELED(2);

    private final int id;

    Label(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static Label fromId(int id) {
        for (Label value : values()) {
            if (value.id == id) {
                return value;
            }
        }

        return UNLABELED;
    }

    public static Label fromString(String value) {
        if (value == null) {
            return null;
        }

        try {
            return Label.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}