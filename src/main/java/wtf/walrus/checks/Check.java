/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.checks;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import wtf.walrus.Main;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.punishment.PunishmentManager;

import java.io.File;

public class Check {

    public final WalrusPlayer player;

    protected static final long NONE = Long.MAX_VALUE;

    protected long closeTransaction = NONE;
    protected int closePacketsToSkip;

    public boolean enabledInConfig;
    private String name, description;
    private double maxBuffer, buffer;
    private int maxVl, vl, ap;

    private long lastVlTime = -1;

    private long vlDecayMs = 300_000L;

    public Check(WalrusPlayer player) {
        this.player = player;
        CheckData data = this.getClass().getAnnotation(CheckData.class);
        if (data == null) {
            throw new IllegalStateException("CheckData annotation missing on " + this.getClass().getSimpleName());
        } else loadCheckData(data);
        loadConfig(data);
    }

    public void onReload(FileConfiguration config) {
    }

    public boolean isRotationUpdate(PacketTypeCommon type) {
        return type.equals(PacketType.Play.Client.PLAYER_ROTATION) || type.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    public boolean isPositionUpdate(PacketTypeCommon type) {
        return type.equals(PacketType.Play.Client.PLAYER_POSITION) || type.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    public void receivePacket(PacketReceiveEvent event) {
        if (isWindowClose(event.getPacketType()) && closePacketsToSkip > 0) {
            closePacketsToSkip--;
            return;
        }
        if (enabledInConfig && this instanceof PacketCheck) ((PacketCheck) this).onPacketReceive(event);
    }

    public void sendPacket(PacketSendEvent event) {
        if (enabledInConfig && this instanceof PacketCheck) ((PacketCheck) this).onPacketSend(event);
    }

    public boolean isWindowClose(PacketTypeCommon type) {
        return type.equals(PacketType.Play.Client.CLOSE_WINDOW);
    }

    public boolean isTickPacket(PacketTypeCommon packetType) {
        if (isTickPacketIncludingNonMovement(packetType)) {
            if (isFlying(packetType)) {
                return !player.lastPacketWasTeleport && !player.lastPacketWasOnePointSeventeenDuplicate;
            }
            return true;
        }
        return false;
    }

    public boolean isTickPacketIncludingNonMovement(PacketTypeCommon packetType) {
        // On 1.21.2+ fall back to the TICK_END packet IF the player did not send a movement packet for their tick
        // TickTimer checks to see if player did not send a tick end packet before new flying packet is sent
        if (player.user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.didSendMovementBeforeTickEnd) {
            if (packetType == PacketType.Play.Client.CLIENT_TICK_END) {
                return true;
            }
        }

        return isFlying(packetType);
    }

    public boolean isFlying(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    public void loadConfig(CheckData data) {
        Main plugin = Main.instance;
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("checks." + getName());

        if (section != null) {
            this.enabledInConfig = section.getBoolean("enabled", true);
            int decaySec = section.getInt("vl-decay", data.decay());
            int alertPer = section.getInt("alert-per", data.ap());
            if (decaySec > 0) {
                this.vlDecayMs = decaySec * 1000L;
            } else {
                this.vlDecayMs = data.decay() * 1000L;
            }
            if (alertPer > 0) {
                this.ap = alertPer;
            } else this.ap = data.ap();
        } else {
            this.enabledInConfig = true;
            this.vlDecayMs = data.decay() * 1000L;
            this.ap = data.ap();
        }
    }

    public void closeInventory() {
        if (closeTransaction != NONE) {
            return;
        }

        int windowId = player.openWindowID;

        player.user.writePacket(new WrapperPlayServerCloseWindow(windowId));

        closePacketsToSkip = 1;
        PacketEvents.getAPI().getProtocolManager().receivePacket(
                player.user.getChannel(), new WrapperPlayClientCloseWindow(windowId)
        );

        player.sendTransaction();
        player.user.flushPackets();
    }

    public void loadCheckData(CheckData data) {
        this.name = data.name();
        this.description = data.description();
        this.maxBuffer = data.maxBuffer();
        this.maxVl = data.maxVl();
    }

    private void tickDecay() {
        if (lastVlTime < 0 || vl <= 0) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastVlTime;

        if (elapsed < vlDecayMs) return;
        vl = 0;
    }

    public boolean flagAndAlert(String verbose) {
        return flag() && alert(verbose);
    }

    public boolean flagAndAlert() {
        return flag() && alert("");
    }

    public boolean flag() {
        addVl();
        punish();
        return true;
    }

    public boolean reward() {
        removeBuffer();
        return true;
    }

    public boolean punish() {
        String playerName = player.user.getName();
        int currentVl     = getVl();
        double currentBuf = getBuffer();
        String checkName  = getName();
        PunishmentManager punishmentManager = Main.instance.getPunishmentManager();
        if (punishmentManager != null) {
            punishmentManager.handleViolation(playerName, checkName, currentVl, currentBuf);
        }
        return true;
    }

    public boolean alert(String verbose) {
        if (vl % ap != 0) return false;

        String playerName = player.user.getName();
        int currentVl     = getVl();
        double currentBuf = getBuffer();
        String checkName  = getName();

        AlertManager alertManager = Main.instance.getAlertManager();
        alertManager.sendAlert(playerName, currentVl, currentBuf, checkName, verbose);

        return true;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(value, maxBuffer * 1.5));
    }

    public double addBuffer(double c) {
        buffer = clamp(buffer + c);
        return buffer;
    }

    public double addBuffer() {
        buffer = clamp(buffer + 1);
        return buffer;
    }

    public double removeBuffer(double c) {
        buffer = clamp(buffer - c);
        return buffer;
    }

    public double removeBuffer() {
        buffer = clamp(buffer - 0.25);
        return buffer;
    }

    public int addVl() {
        tickDecay();
        lastVlTime = System.currentTimeMillis();
        return ++vl;
    }

    public int removeVl() {
        tickDecay();
        return --vl;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public double getBuffer() { return buffer; }
    public double getMaxBuffer() { return maxBuffer; }
    public int getMaxVl() { return maxVl; }

    public int getVl() {
        tickDecay();
        return vl;
    }
}