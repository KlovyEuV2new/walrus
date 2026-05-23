package wtf.walrus.bans.config;

import wtf.walrus.bans.BansManager;
import wtf.walrus.bans.config.impl.BDBConfig;

public class BansConfig {
    public final BansManager manager;

    public final BDBConfig bdbConfig;

    public BansConfig(BansManager manager) {
        this.manager = manager;

        this.bdbConfig = new BDBConfig(this);
    }
}
