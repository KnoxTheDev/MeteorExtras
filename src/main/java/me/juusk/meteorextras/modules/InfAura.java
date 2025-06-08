package me.juusk.meteorextras.modules;

import me.juusk.meteorextras.MeteorExtras;
import me.juusk.meteorextras.utils.ModuleUtils;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
// import net.minecraft.item.SwordItem; // SwordItem class not found
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
// import java.util.Set; // No longer needed for EntityTypeListSetting
import java.util.function.Predicate;

public class InfAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Weapon> weapon = sgGeneral.add(new EnumSetting.Builder<Weapon>()
        .name("weapon")
        .description("Only attacks an entity when a specified weapon is in your hand.")
        .defaultValue(Weapon.All)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target. (Not recommended)")
        .defaultValue(RotationMode.None)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to your selected weapon when attacking the target.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only attacks when holding left click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnLook = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-look")
        .description("Only attacks when looking at an entity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Freezes Baritone temporarily until you are finished attacking the entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("Will try and use an axe to break target shields.")
        .defaultValue(ShieldMode.Break)
        .visible(() -> autoSwitch.get() && weapon.get() != Weapon.Axe)
        .build()
    );

    // Targeting
    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many entities to target at once.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .visible(() -> !onlyOnLook.get())
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to attack it.")
        .defaultValue(20)
        .min(0)
        .sliderMax(100)
        .build()
    );


    private final Setting<Double> perBlink = sgTargeting.add(new DoubleSetting.Builder()
        .name("per-blink")
        .description("After how many blocks it teleports")
        .defaultValue(8.5)
        .min(2)
        .sliderMax(20)
        .build()
    );


    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls.")
        .defaultValue(10)
        .min(0)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Whether or not to attack players with a custom name.")
        .defaultValue(false)
        .build()
    );

    // Timing
    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses if the server is lagging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Does not attack while CA is placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Tries to sync attack delay with the server's TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Use a custom delay instead of the vanilla cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("How fast you hit the entity in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("How many ticks to wait before hitting an entity after switching hotbar slots.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final List<Entity> targets = new ArrayList<>();
    private int switchTimer, hitTimer;
    private boolean wasPathing = false;
    public boolean attacking;

    public InfAura() {
        super(MeteorExtras.CATEGORY, "InfAura-Players", "KillAura with infinite reach, targets Players ONLY.");
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        attacking = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive() || PlayerUtils.getGameMode() == GameMode.SPECTATOR) return;
        if (pauseOnUse.get() && (mc.interactionManager != null && mc.interactionManager.isBreakingBlock() || mc.player.isUsingItem())) return;
        if (onlyOnClick.get() && (mc.options == null || !mc.options.attackKey.isPressed())) return;
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f && pauseOnLag.get()) return;
        if (pauseOnCA.get()) {
            CrystalAura crystalAura = Modules.get().get(CrystalAura.class);
            if (crystalAura.isActive() && crystalAura.kaTimer > 0) return;
        }

        if (onlyOnLook.get()) {
            Entity targeted = mc.targetedEntity;
            if (targeted == null || !entityCheck(targeted)) { // entityCheck now hardcodes to players
                targets.clear(); // Clear targets if look target is invalid
            } else {
                targets.clear();
                targets.add(targeted);
            }
        } else {
            targets.clear();
            TargetUtils.getList(targets, this::entityCheck, priority.get(), maxTargets.get());
        }

        if (targets.isEmpty()) {
            attacking = false;
            if (wasPathing) {
                PathManagers.get().resume();
                wasPathing = false;
            }
            return;
        }

        Entity primary = targets.getFirst(); // Should always be a PlayerEntity now

        if (autoSwitch.get()) {
            Predicate<ItemStack> predicate = switch (weapon.get()) {
                case Axe -> stack -> stack.getItem() instanceof AxeItem;
                case Sword -> stack -> false; // SwordItem class not found
                case Mace -> stack -> stack.getItem() instanceof MaceItem;
                case Trident -> stack -> stack.getItem() instanceof TridentItem;
                case All -> stack -> stack.getItem() instanceof AxeItem || /* SwordItem check removed */ stack.getItem() instanceof MaceItem || stack.getItem() instanceof TridentItem;
                default -> o -> true;
            };
            FindItemResult weaponResult = InvUtils.findInHotbar(predicate);

            if (shouldShieldBreak((PlayerEntity) primary)) { // Pass player directly
                FindItemResult axeResult = InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof AxeItem);
                if (axeResult.found()) weaponResult = axeResult;
            }

            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!itemInHand((PlayerEntity) primary)) return; // Pass player directly

        attacking = true;
        if (rotation.get() == RotationMode.Always) Rotations.rotate(Rotations.getYaw(primary), Rotations.getPitch(primary, Target.Body));
        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (delayCheck()) targets.forEach(this::attack); // All targets are players
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    // Modified to take PlayerEntity directly since targets list only contains players
    private boolean shouldShieldBreak(PlayerEntity player) {
        if (mc.world == null || mc.player == null) return false;
        // Only need to check the passed player, as the list is homogeneous
        return player.isBlocking() && shieldMode.get() == ShieldMode.Break;
    }


    private boolean entityCheck(Entity entity) {
        if (mc.player == null || mc.world == null) return false;

        // --- HARDCODED PLAYER ONLY TARGETING ---
        if (!(entity instanceof PlayerEntity player)) return false; // Cast directly if PlayerEntity
        // --- END HARDCODED ---

        if (player.equals(mc.player) || player.equals(mc.cameraEntity)) return false;
        if (player.isDead() || !player.isAlive()) return false; // Use player directly

        Box hitbox = player.getBoundingBox();
        if (!PlayerUtils.isWithin(
            MathHelper.clamp(mc.player.getX(), hitbox.minX, hitbox.maxX),
            MathHelper.clamp(mc.player.getY(), hitbox.minY, hitbox.maxY),
            MathHelper.clamp(mc.player.getZ(), hitbox.minZ, hitbox.maxZ),
            range.get()
        )) return false;

        // ignoreNamed can still apply to players
        if (ignoreNamed.get() && player.hasCustomName()) return false;

        if (!PlayerUtils.canSeeEntity(player) && !PlayerUtils.isWithin(player, wallsRange.get())) return false;

        // Player-specific checks (already know it's a PlayerEntity)
        if (player.isCreative()) return false;
        if (Friends.get() != null && !Friends.get().shouldAttack(player)) return false;
        if (shieldMode.get() == ShieldMode.Ignore && player.isBlocking()) return false;

        return true;
    }

    private boolean delayCheck() {
        if (mc.player == null) return false;
        if (switchTimer > 0) {
            switchTimer--;
            return false;
        }

        float delayValue = (customDelay.get()) ? hitDelay.get() : 0.5f; // Renamed 'delay' to 'delayValue'
        if (tpsSync.get() && TickRate.INSTANCE.getTickRate() > 0) {
             delayValue /= (TickRate.INSTANCE.getTickRate() / 20);
        }

        if (customDelay.get()) {
            if (hitTimer < delayValue) {
                hitTimer++;
                return false;
            } else {
                // hitTimer reset in attack()
                return true;
            }
        } else {
            return mc.player.getAttackCooldownProgress(0.5f) >= 1;
        }
    }


    private void attack(Entity target) { // target will always be a PlayerEntity
        if (mc.player == null || mc.interactionManager == null) return;
        if (rotation.get() == RotationMode.OnHit) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body));

        Vec3d originalPos = mc.player.getPos();

        ModuleUtils.splitTeleport(originalPos, target.getPos(), perBlink.get());
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        ModuleUtils.splitTeleport(target.getPos(), originalPos, perBlink.get());

        hitTimer = 0;
    }

    // Modified to take PlayerEntity directly
    private boolean itemInHand(PlayerEntity playerContext) { // playerContext is the current target
        if (mc.player == null) return false;
        if (shouldShieldBreak(playerContext)) return mc.player.getMainHandStack().getItem() instanceof AxeItem;

        return switch (weapon.get()) {
            case Axe -> mc.player.getMainHandStack().getItem() instanceof AxeItem;
            case Sword -> false; // SwordItem class not found
            case Mace -> mc.player.getMainHandStack().getItem() instanceof MaceItem;
            case Trident -> mc.player.getMainHandStack().getItem() instanceof TridentItem;
            case All -> mc.player.getMainHandStack().getItem() instanceof AxeItem || /* SwordItem check removed */ mc.player.getMainHandStack().getItem() instanceof MaceItem || mc.player.getMainHandStack().getItem() instanceof TridentItem;
            default -> true;
        };
    }

    public Entity getTarget() {
        if (!targets.isEmpty()) return targets.getFirst(); // Will be a PlayerEntity
        return null;
    }

    @Override
    public String getInfoString() {
        if (!targets.isEmpty()) return EntityUtils.getName(getTarget());
        return null;
    }

    public enum Weapon {
        Sword,
        Axe,
        Mace,
        Trident,
        All,
        Any
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    public enum ShieldMode {
        Ignore,
        Break,
        None
    }
