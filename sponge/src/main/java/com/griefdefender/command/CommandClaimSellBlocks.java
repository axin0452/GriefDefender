/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import com.google.common.collect.ImmutableMap;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.claim.ClaimResultType;
import com.griefdefender.cache.MessageCache;
import com.griefdefender.claim.GDClaimResult;
import com.griefdefender.configuration.MessageStorage;
import com.griefdefender.permission.GDPermissions;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.adapter.spongeapi.TextAdapter;
import net.kyori.text.format.TextColor;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.CauseStackManager;
import org.spongepowered.api.service.economy.Currency;
import org.spongepowered.api.service.economy.account.Account;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;

import java.math.BigDecimal;

@CommandAlias("%griefdefender")
@CommandPermission(GDPermissions.COMMAND_SELL_CLAIM_BLOCKS)
public class CommandClaimSellBlocks extends BaseCommand {

    @CommandAlias("sellclaim|sellclaimblocks|sellblocks")
    @Description("Sell your claim blocks for server money.\nNote: Requires economy plugin.")
    @Syntax("[<amount>]")
    @Subcommand("sell blocks")
    public void execute(Player player, @Optional Integer blockCount) {
        final boolean economyMode = GriefDefenderPlugin.getInstance().isEconomyModeEnabled();
        // if economy is disabled, don't do anything
        if (!GriefDefenderPlugin.getInstance().economyService.isPresent()) {
            GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().ECONOMY_NOT_INSTALLED);
            return;
        }

        final GDPlayerData playerData = GriefDefenderPlugin.getInstance().dataStore.getOrCreatePlayerData(player.getWorld(), player.getUniqueId());
        if (playerData.getEconomyClaimBlockReturn() <= 0) {
            GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().ECONOMY_BLOCK_BUY_SELL_DISABLED);
            return;
        }

        // if selling disabled, send error message
        if (playerData.getEconomyClaimBlockReturn() == 0) {
            GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().ECONOMY_BLOCK_ONLY_BUY);
            return;
        }

        final Account playerAccount = GriefDefenderPlugin.getInstance().economyService.get().getOrCreateAccount(player.getUniqueId()).orElse(null);
        if (playerAccount == null) {
            final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_PLAYER_NOT_FOUND, ImmutableMap.of(
                    "player", player.getName()));
            GriefDefenderPlugin.sendMessage(player, message);
            return;
        }

        int availableBlocks = economyMode ? playerData.getAccruedClaimBlocks() + playerData.getBonusClaimBlocks() : playerData.getInternalRemainingClaimBlocks();
        if (blockCount == null) {
            final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_BLOCK_PURCHASE_COST,
                    ImmutableMap.of(
                    "amount", playerData.getEconomyClaimBlockReturn(),
                    "balance", availableBlocks));
            GriefDefenderPlugin.sendMessage(player, message);
            return;
        } else {
            // try to parse number of blocks
            if (blockCount <= 0) {
                GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().ECONOMY_BLOCK_BUY_INVALID);
                return;
            } else if (blockCount > availableBlocks) {
                GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().ECONOMY_BLOCK_NOT_AVAILABLE);
                return;
            }

            // attempt to compute value and deposit it
            double economyTotalValue = blockCount * playerData.getEconomyClaimBlockReturn();
            final Currency defaultCurrency = GriefDefenderPlugin.getInstance().economyService.get().getDefaultCurrency();
            final TransactionResult result = playerAccount.deposit(defaultCurrency, BigDecimal.valueOf(economyTotalValue), Sponge.getCauseStackManager().getCurrentCause());
            if (result.getResult() != ResultType.SUCCESS) {
                final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_BLOCK_SELL_ERROR, ImmutableMap.of(
                        "reason", result.getResult()));
                GriefDefenderPlugin.sendMessage(player, message);
                return;
            }

            final BigDecimal currentFunds = playerAccount.getBalance(defaultCurrency);
            Component message = null;
            if (economyMode) {
                message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_MODE_BLOCK_SALE_CONFIRMATION,
                        ImmutableMap.of(
                        "deposit", economyTotalValue,
                        "balance", String.valueOf("$" + currentFunds),
                        "amount", playerData.getRemainingClaimBlocks()));
            } else {
                message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_BLOCK_SALE_CONFIRMATION,
                    ImmutableMap.of(
                    "deposit", economyTotalValue,
                    "amount", playerData.getRemainingClaimBlocks()));
            }
            int bonusBlocks = playerData.getBonusClaimBlocks();
            int accruedBlocks = playerData.getAccruedClaimBlocks();
            if (bonusBlocks > 0) {
                if (bonusBlocks >= blockCount) {
                    bonusBlocks = (int) (bonusBlocks - blockCount);
                    playerData.setBonusClaimBlocks(bonusBlocks);
                } else {
                    int remaining = (int) (blockCount - bonusBlocks);
                    playerData.setBonusClaimBlocks(0);
                    playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - remaining);
                }
            } else {
                accruedBlocks = (int) (accruedBlocks - blockCount);
                playerData.setAccruedClaimBlocks(accruedBlocks);
            }
            playerData.saveAllData();
            GriefDefenderPlugin.sendMessage(player, message);
        }
    }
}