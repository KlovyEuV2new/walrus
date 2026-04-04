package wtf.walrus.checks.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.util.MetadataIndex;
import wtf.walrus.util.WatchableIndexUtil;

import java.util.UUID;

public class CheckManagerListener extends PacketListenerAbstract {

    public CheckManagerListener() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUUID();

        WalrusPlayer player = WalrusPlayer.get(uuid);
        if (player == null) return;

        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction wrapper = new WrapperPlayClientEntityAction(event);
            switch (wrapper.getAction()) {
                case START_SPRINTING -> {
                    player.lastStartSprint = System.nanoTime();
                    player.wasSprinting = player.isSprinting;
                    player.isSprinting = true;
                }
                case STOP_SPRINTING -> {
                    player.lastStopSprint = System.nanoTime();
                    player.wasSprinting = player.isSprinting;
                    player.isSprinting = false;
                }
                case START_SNEAKING -> {
                    player.lastStartSneaking = System.nanoTime();
                    player.wasSneaking = player.isSneaking;
                    player.isSneaking = true;
                }
                case STOP_SNEAKING -> {
                    player.lastStopSneaking = System.nanoTime();
                    player.wasSneaking = player.isSneaking;
                    player.isSneaking = false;
                }
                case START_FLYING_WITH_ELYTRA -> player.isGliding = true;
            }
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            player.lastInteract = System.nanoTime();
            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                player.lastAttack = System.nanoTime();
            }
        } else if (type == PacketType.Play.Client.PLAYER_ABILITIES) {
            WrapperPlayClientPlayerAbilities wrapper = new WrapperPlayClientPlayerAbilities(event);
            player.isFlying = wrapper.isFlying();
            player.isAllowedFlight = wrapper.isFlightAllowed().orElse(false);
            player.flySpeed = wrapper.getFlySpeed().orElse(0.0f);
            player.godMode = wrapper.isInGodMode().orElse(false);
            player.creativeAbility = wrapper.isInCreativeMode().orElse(false);
        } else if (type == PacketType.Play.Client.CHANGE_GAME_MODE) {
            WrapperPlayClientChangeGameMode wrapper = new WrapperPlayClientChangeGameMode(event);
            player.gameMode = wrapper.getGameMode();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange wrapper = new WrapperPlayClientHeldItemChange(event);
            player.heldSlot = wrapper.getSlot();
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            player.inventoryOpened = false;
            player.openWindowID = 0;
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            player.inventoryOpened = true;
        } else if (type == PacketType.Play.Client.PLAYER_POSITION) {
            WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
            player.lastPosition = player.position;
            player.position = wrapper.getPosition();
            player.lastDeltaXZ = player.deltaXZ;
            player.lastDeltaX = player.deltaX;
            player.lastDeltaY = player.deltaY;
            player.lastDeltaZ = player.deltaZ;
            player.deltaXZ = player.deltaXZ();
            player.deltaY = player.deltaY();
            player.deltaZ = player.deltaZ();
            player.deltaX = player.deltaX();
        } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
            player.lastPitch = player.pitch;
            player.lastYaw = player.yaw;
            player.pitch = wrapper.getPitch();
            player.yaw = wrapper.getYaw();
            player.lastDeltaYaw = player.deltaYaw;
            player.lastDeltaPitch = player.deltaPitch;
            player.deltaYaw = player.lastYaw - player.yaw;
            player.deltaPitch = player.lastPitch - player.pitch;
            player.rotationData.handle(player.yaw, player.pitch);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
            player.lastPosition = player.position;
            player.lastPitch = player.pitch;
            player.lastYaw = player.yaw;
            player.position = wrapper.getPosition();
            player.lastDeltaXZ = player.deltaXZ;
            player.lastDeltaX = player.deltaX;
            player.lastDeltaY = player.deltaY;
            player.lastDeltaZ = player.deltaZ;
            player.deltaXZ = player.deltaXZ();
            player.deltaY = player.deltaY();
            player.deltaZ = player.deltaZ();
            player.deltaX = player.deltaX();
            player.pitch = wrapper.getPitch();
            player.yaw = wrapper.getYaw();
            player.lastDeltaYaw = player.deltaYaw;
            player.lastDeltaPitch = player.deltaPitch;
            player.deltaYaw = player.lastYaw - player.yaw;
            player.deltaPitch = player.lastPitch - player.pitch;
            player.rotationData.handle(player.yaw, player.pitch);
        } else if (type == PacketType.Play.Client.STEER_VEHICLE) {
            player.inVehicle = true;
        }

        if (isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
            player.didSendMovementBeforeTickEnd = true;

            if (player.lastPosition != null) {
                double x = player.position.getX();
                double y = player.position.getY();
                double z = player.position.getZ();

                float yaw = player.yaw;
                float pitch = player.pitch;

                boolean onGround = wrapper.isOnGround();

                player.lastPacketWasOnePointSeventeenDuplicate =
                        x == player.lastPosition.getX() &&
                                y == player.lastPosition.getY() &&
                                z == player.lastPosition.getZ() &&
                                yaw == player.lastYaw &&
                                pitch == player.lastPitch &&
                                onGround == player.lastOnGround;

                player.lastOnGround = player.onGround;
                player.onGround = onGround;
            } else {
                player.lastPosition = player.position;
                player.lastYaw = player.yaw;
                player.lastPitch = player.pitch;
                player.lastOnGround = wrapper.isOnGround();
                player.lastPacketWasOnePointSeventeenDuplicate = false;
                player.onGround = wrapper.isOnGround();
            }
        }

        player.checkManager.onPacketReceive(event);

        if (isFlying(event.getPacketType())) {
            player.lastPacketWasTeleport = false;
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            player.didSendMovementBeforeTickEnd = false;
        }
    }

    public boolean isFlying(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUUID();

        WalrusPlayer player = WalrusPlayer.get(uuid);
        if (player != null) player.onDisconnect();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUUID();

        WalrusPlayer player = WalrusPlayer.get(uuid);
        PacketTypeCommon type = event.getPacketType();

        if (player == null) {
            if (event.getPacketType() == PacketType.Login.Server.LOGIN_SUCCESS) {
                event.getTasksAfterSend().add(createPlayer(user));
            }
            return;
        }

        if (type == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            WrapperPlayServerHeldItemChange wrapper = new WrapperPlayServerHeldItemChange(event);
            player.heldSlot = wrapper.getSlot();
        } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
            player.inventoryOpened = false;
            player.openWindowID = 0;
        } else if (type == PacketType.Play.Server.OPEN_WINDOW) {
            WrapperPlayServerOpenWindow wrapper = new WrapperPlayServerOpenWindow(event);
            player.inventoryOpened = true;
            player.openWindowID = wrapper.getContainerId();
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
            if (wrapper.getEntityId() == player.user.getEntityId()) {
                EntityData<?> watchable = WatchableIndexUtil.getIndex(wrapper.getEntityMetadata(), 0);
                if (watchable != null) {
                    byte field = (byte) watchable.getValue();
                    player.wasGliding = player.isGliding;
                    player.wasSwimming = player.isSwimming;
                    player.isGliding = (field & 0x80) != 0 && player.user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
                    player.isSwimming = (field & 0x10) != 0;
                }
                wrapper.getEntityMetadata().stream().filter(d -> d.getIndex() == MetadataIndex.HEALTH)
                        .findFirst().ifPresent(d -> player.health = (float) d.getValue());
                wrapper.getEntityMetadata().stream().filter(d -> d.getIndex() == MetadataIndex.ABSORPTION)
                        .findFirst().ifPresent(d -> player.absorption = (float) d.getValue());
                wrapper.getEntityMetadata().stream().filter(d -> d.getIndex() == MetadataIndex.XP)
                        .findFirst().ifPresent(d -> player.xp = (int) d.getValue());
            }
        } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers mount = new WrapperPlayServerSetPassengers(event);
            handleVehicleUpdate(player, event, mount.getEntityId(), mount.getPassengers());
        } else if (type == PacketType.Play.Server.ATTACH_ENTITY) {
            WrapperPlayServerAttachEntity attach = new WrapperPlayServerAttachEntity(event);
            if (!attach.isLeash()) {
                int vehicleID = attach.getHoldingId();
                int attachID = attach.getAttachedId();
                if (vehicleID == -1) {
                    handleVehicleUpdate(player, event, attachID, new int[]{});
                } else {
                    handleVehicleUpdate(player, event, vehicleID, new int[]{attachID});
                }
            }
        } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            player.lastPacketWasTeleport = true;
        }

        player.checkManager.onPacketSend(event);
    }

    public Runnable createPlayer(User user) {
        return () -> {
            new WalrusPlayer(user, user.getUUID());
        };
    }

    private void handleVehicleUpdate(WalrusPlayer player, PacketSendEvent event, int vehicleID, int[] passengers) {
        boolean inThisVehicle = false;
        for (int passenger : passengers) {
            if (passenger == player.user.getEntityId()) {
                inThisVehicle = true;
                break;
            }
        }
        player.inVehicle = inThisVehicle;
        player.sendTransaction();
    }
}