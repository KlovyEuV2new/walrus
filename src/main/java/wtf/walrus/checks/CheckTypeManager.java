package wtf.walrus.checks;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public class CheckTypeManager {
    private final List<CType> types = new ArrayList<>();

    public CheckTypeManager(FileConfiguration config) {
        loadTypes(config);
    }

    public void loadTypes(FileConfiguration config) {
        ConfigurationSection typesSection = config.getConfigurationSection("detection.types");
        if (typesSection != null) {
            this.types.clear();
            for (String k : typesSection.getKeys(false)) {
                ConfigurationSection typeSection = typesSection.getConfigurationSection(k);
                if (typeSection != null) {
                    CheckType checkType = null;
                    try {
                        checkType = CheckType.valueOf(k.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    if (checkType == null) return;
                    String name = typeSection.getString("name", checkType.name());
                    this.types.add(new CType(checkType, name));
                }
            }
        }
    }

    public String getName(CheckType type) {
        for (CType t : this.types) if (t.type().equals(type)) return t.name();
        return type.name();
    }
}
