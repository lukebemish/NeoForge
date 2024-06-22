/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.attachment;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.serialization.Codec;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.registration.RegistrationHelper;

@ForEachTest(groups = "attachment")
public class AttachmentTests {
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Ensures that chunk attachments can capture a reference to the containing LevelChunk.")
    static void chunkAttachmentReferenceTest(DynamicTest test, RegistrationHelper reg) {
        class ChunkMutableInt {
            private LevelChunk chunk;
            private int value;

            public static final Codec<ChunkMutableInt> CODEC = Codec.INT.xmap(ChunkMutableInt::new, ChunkMutableInt::getValue);

            public ChunkMutableInt(LevelChunk chunk, int value) {
                this.chunk = chunk;
                this.value = value;
            }

            public ChunkMutableInt(int value) {
                this.value = value;
            }

            public int getValue() {
                return value;
            }

            public void setValue(int value) {
                this.value = value;
                chunk.setUnsaved(true);
            }
        }

        var attachmentType = reg.registrar(NeoForgeRegistries.Keys.ATTACHMENT_TYPES)
                .register("chunk_mutable_int", () -> AttachmentType.builder(chunk -> new ChunkMutableInt((LevelChunk) chunk, 0))

                        // use a codec for serialization
                        .serialize(ChunkMutableInt.CODEC)

                        // you can either use a different codec for deserializing, or omit to re-use the serializer
                        // here, you also get a "last-chance" to pull data from the attachment holder before
                        // the attachment is finished constructing from a codec
                        .deserialize(ChunkMutableInt.CODEC, (holder, thing) -> {
                            thing.chunk = (LevelChunk) holder;
                            return thing;
                        })
                        .build());

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            event.getDispatcher()
                    .register(literal(test.id())
                            .then(literal("print_and_increment")
                                    .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                                    .executes(ctx -> {
                                        var chunk = ctx.getSource().getLevel().getChunkAt(BlockPos.containing(ctx.getSource().getPosition()));
                                        var attachment = chunk.getData(attachmentType);
                                        attachment.setValue(attachment.getValue() + 1);
                                        ctx.getSource().sendSuccess(() -> Component.literal("New attachment value: " + attachment.getValue()), false);
                                        return Command.SINGLE_SUCCESS;
                                    })));
        });

        test.onGameTest(helper -> {
            var player = helper.makeOpMockPlayer(Commands.LEVEL_OWNERS);
            var pos = helper.absolutePos(BlockPos.ZERO);
            player.setPos(pos.getCenter());

            helper.getLevel().getChunk(pos).removeData(attachmentType); // remove data to ensure that the test can run multiple times

            helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), test.id() + " print_and_increment");
            helper.assertTrue(((LevelChunk) helper.getLevel().getChunk(pos)).getData(attachmentType).getValue() == 1,
                    "Chunk attachment value should have been 1");

            helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), test.id() + " print_and_increment");
            helper.assertTrue(((LevelChunk) helper.getLevel().getChunk(pos)).getData(attachmentType).getValue() == 2,
                    "Chunk attachment value should have been 2");

            helper.succeed();
        });
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Ensures that player attachments are copied on respawn when appropriate.")
    static void playerAttachmentCopyOnRespawn(DynamicTest test, RegistrationHelper reg) {
        var lostOnDeathBoolean = reg.attachments()
                .register("lost_on_death_boolean", () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).build());
        var keptOnDeathBoolean = reg.attachments()
                .register("kept_on_death_boolean", () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

        test.onGameTest(helper -> {
            var player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            player.setData(lostOnDeathBoolean, true);
            player.setData(keptOnDeathBoolean, true);

            var returningPlayer = player.getServer().getPlayerList().respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION);

            helper.assertTrue(returningPlayer.getData(lostOnDeathBoolean), "Lost-on-death attachment should have remained after end portal respawning.");
            helper.assertTrue(returningPlayer.getData(keptOnDeathBoolean), "Kept-on-death attachment should have remained after end portal respawning.");

            var respawnedPlayer = player.getServer().getPlayerList().respawn(returningPlayer, false, Entity.RemovalReason.KILLED);

            helper.assertFalse(respawnedPlayer.getData(lostOnDeathBoolean), "Lost-on-death attachment should not have remained after respawning.");
            helper.assertTrue(respawnedPlayer.getData(keptOnDeathBoolean), "Kept-on-death attachment should have remained after respawning.");

            helper.succeed();
        });
    }
}
