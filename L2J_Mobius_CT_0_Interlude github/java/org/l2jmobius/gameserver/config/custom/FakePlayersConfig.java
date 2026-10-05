/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * This class loads all the custom fake player related configurations.
 * @author Mobius
 */
public class FakePlayersConfig
{
	// Constants
	public static boolean FAKE_PLAYERS_ENABLED;
	public static boolean FAKE_PLAYER_CHAT;
	public static boolean FAKE_PLAYER_BEHAVIOR;
	public static int FAKE_PLAYER_DEPLOY_COUNT;
	public static int FAKE_PLAYER_BASE_NPC_ID;
	public static boolean FAKE_PLAYER_USE_SHOTS;
	public static boolean FAKE_PLAYER_KILL_PVP;
	public static boolean FAKE_PLAYER_KILL_KARMA;
	public static boolean FAKE_PLAYER_AUTO_ATTACKABLE;
	public static boolean FAKE_PLAYER_AGGRO_MONSTERS;
	public static boolean FAKE_PLAYER_AGGRO_PLAYERS;
	public static boolean FAKE_PLAYER_AGGRO_FPC;
	public static boolean FAKE_PLAYER_CAN_DROP_ITEMS;
	public static boolean FAKE_PLAYER_CAN_PICKUP;
	public static boolean FAKE_PLAYER_PARTY_QUEST_CREDIT;
	public static int FAKE_PLAYER_PARTY_QUEST_CREDIT_RANGE;
	public static boolean FAKE_PLAYER_PARTY_EXP_SHARE;
	public static boolean FAKE_PLAYER_PARTY_LOOT_SHARE;
	public static boolean FAKE_PLAYER_PARTY_PICKUP;
	public static boolean FAKE_PLAYER_RAID_TANK_HATE;
	public static double FAKE_PLAYER_RAID_TANK_HATE_MARGIN;
	public static int FAKE_PLAYER_RAID_HUMAN_TANK_GRACE;
	public static int FAKE_PLAYER_RECRUIT_ENCHANT_CHANCE;
	public static int FAKE_PLAYER_RECRUIT_ENCHANT_MIN;
	public static int FAKE_PLAYER_RECRUIT_ENCHANT_MAX;
	public static boolean FAKE_PLAYER_AUTO_HUNTING_ZONES;
	public static boolean FAKE_PLAYER_MEET_RECALL;
	public static int FAKE_PLAYER_MEET_RECALL_CAST_SECONDS;
	public static int FAKE_PLAYER_MEET_RECALL_MIN_DISTANCE;
	public static int FAKE_PLAYER_MEET_RECALL_STUCK_SECONDS;
	public static boolean FAKE_PLAYER_MEET_NEAR_PLAYER;
	public static boolean PHANTOM_HUNTER_PLAYSTYLES;
	public static boolean PHANTOM_SKILL_FALLBACK;
	public static boolean PHANTOM_COMBAT_CONTROLLER;
	public static boolean PHANTOM_ARCHER_KITING;
	public static boolean PHANTOM_HUNTER_RETALIATE;
	public static boolean PHANTOM_PVP_ENABLED;
	public static boolean PHANTOM_PVP_SELF_DEFENSE;
	public static boolean PHANTOM_PVP_REACT_TO_FLAGGED;
	public static boolean PHANTOM_PVP_DUELS;
	public static boolean PHANTOM_PVP_PARTY_DEFENSE;
	public static boolean PHANTOM_PVP_BETWEEN_PHANTOMS;
	public static boolean PHANTOM_PVP_OPEN_WORLD_GANK;
	public static boolean PHANTOM_PVP_DEATH_DROPS;
	public static int PHANTOM_PVP_MAX_LEVEL_GAP_ABOVE_PLAYER;
	public static int PHANTOM_PVP_AGGRESSOR_PERCENT;
	public static int PHANTOM_PVP_REACT_CHANCE_PERCENT;
	public static int PHANTOM_PVP_RED_REACT_CHANCE_PERCENT;
	public static boolean PHANTOM_PVP_RED_REACT_ALL;
	public static int PHANTOM_PVP_ENGAGE_COOLDOWN_SECONDS;
	public static boolean PHANTOM_PVP_CLAN_DEFENSE;
	public static int PHANTOM_PVP_DEFEND_RADIUS;
	public static int PHANTOM_PVP_DUEL_CHANCE_PERCENT;
	public static int PHANTOM_PVP_FLEE_HP_PERCENT;
	public static int FAKE_PLAYER_AMBIENT_TRADE_INTERVAL_SECONDS;
	public static int FAKE_PLAYER_AMBIENT_SHOUT_INTERVAL_SECONDS;
	public static int FAKE_PLAYER_MAX_PUBLIC_CHATS_PER_MINUTE;
	public static int FAKE_PLAYER_BOT_CHAT_CHAIN_DEPTH;
	public static int FAKE_PLAYER_BOT_CHAT_REPLY_CHANCE;
	// WTS/WTB reliability (all switchable; defaults on, set False to get the old behaviour back).
	public static boolean TRADE_AD_PARSER_V2;
	public static boolean TRADE_AD_LINKED_ITEMS;
	public static boolean TRADE_AD_STATUS_REPLIES;
	public static boolean TRADE_AD_FORMAT_HINT;
	public static int TRADE_AD_OFFERS_PER_MINUTE;
	public static int TRADE_AD_OFFERS_PER_PLAYER_PER_MINUTE;
	public static int TRADE_AD_REPLY_MIN_MS;
	public static int TRADE_AD_REPLY_MAX_MS;
	public static boolean TRADE_AD_CLARIFY;
	public static int TRADE_AD_MAX_ITEMS;
	public static boolean TRADE_AD_ENCHANT_PRICING;
	public static int TRADE_OFFER_TIMEOUT_SECONDS;

	public static void load(String baseConfigPath)
	{
		String fakePlayersConfigFile = String.format("./%s/Custom/FakePlayers.ini", baseConfigPath);
		final ConfigReader config = new ConfigReader(fakePlayersConfigFile);
		FAKE_PLAYERS_ENABLED = config.getBoolean("EnableFakePlayers", false);
		FAKE_PLAYER_CHAT = config.getBoolean("FakePlayerChat", false);
		FAKE_PLAYER_BEHAVIOR = config.getBoolean("FakePlayerBehavior", false);
		FAKE_PLAYER_DEPLOY_COUNT = config.getInt("FakePlayerDeployCount", 0);
		FAKE_PLAYER_BASE_NPC_ID = config.getInt("FakePlayerBaseNpcId", 0);
		FAKE_PLAYER_USE_SHOTS = config.getBoolean("FakePlayerUseShots", false);
		FAKE_PLAYER_KILL_PVP = config.getBoolean("FakePlayerKillsRewardPvP", false);
		FAKE_PLAYER_KILL_KARMA = config.getBoolean("FakePlayerUnflaggedKillsKarma", false);
		FAKE_PLAYER_AUTO_ATTACKABLE = config.getBoolean("FakePlayerAutoAttackable", false);
		FAKE_PLAYER_AGGRO_MONSTERS = config.getBoolean("FakePlayerAggroMonsters", false);
		FAKE_PLAYER_AGGRO_PLAYERS = config.getBoolean("FakePlayerAggroPlayers", false);
		FAKE_PLAYER_AGGRO_FPC = config.getBoolean("FakePlayerAggroFPC", false);
		FAKE_PLAYER_CAN_DROP_ITEMS = config.getBoolean("FakePlayerCanDropItems", false);
		FAKE_PLAYER_CAN_PICKUP = config.getBoolean("FakePlayerCanPickup", false);
		FAKE_PLAYER_PARTY_QUEST_CREDIT = config.getBoolean("FakePlayerPartyQuestCredit", true);
		FAKE_PLAYER_PARTY_QUEST_CREDIT_RANGE = config.getInt("FakePlayerPartyQuestCreditRange", 1500);
		FAKE_PLAYER_PARTY_EXP_SHARE = config.getBoolean("FakePlayerPartyExpShare", false);
		FAKE_PLAYER_PARTY_LOOT_SHARE = config.getBoolean("FakePlayerPartyLootShare", false);
		FAKE_PLAYER_PARTY_PICKUP = config.getBoolean("FakePlayerPartyPickup", true);
		FAKE_PLAYER_RAID_TANK_HATE = config.getBoolean("FakePlayerRaidTankHate", true);
		FAKE_PLAYER_RAID_TANK_HATE_MARGIN = Math.max(1.0, config.getDouble("FakePlayerRaidTankHateMargin", 1.5));
		FAKE_PLAYER_RAID_HUMAN_TANK_GRACE = Math.max(0, config.getInt("FakePlayerRaidHumanTankGraceSeconds", 5));
		FAKE_PLAYER_RECRUIT_ENCHANT_CHANCE = config.getInt("FakePlayerRecruitEnchantChance", 65);
		FAKE_PLAYER_RECRUIT_ENCHANT_MIN = config.getInt("FakePlayerRecruitEnchantMin", 3);
		FAKE_PLAYER_RECRUIT_ENCHANT_MAX = config.getInt("FakePlayerRecruitEnchantMax", 6);
		FAKE_PLAYER_AUTO_HUNTING_ZONES = config.getBoolean("PhantomAutoHuntingZones", true);
		FAKE_PLAYER_MEET_RECALL = config.getBoolean("FakePlayerMeetRecall", true);
		FAKE_PLAYER_MEET_RECALL_CAST_SECONDS = Math.max(1, config.getInt("FakePlayerMeetRecallCastSeconds", 4));
		FAKE_PLAYER_MEET_RECALL_MIN_DISTANCE = Math.max(0, config.getInt("FakePlayerMeetRecallMinDistance", 500));
		FAKE_PLAYER_MEET_RECALL_STUCK_SECONDS = Math.max(5, config.getInt("FakePlayerMeetRecallStuckSeconds", 10));
		FAKE_PLAYER_MEET_NEAR_PLAYER = config.getBoolean("FakePlayerMeetNearPlayer", true);
		PHANTOM_HUNTER_PLAYSTYLES = config.getBoolean("PhantomHunterPlaystyles", true);
		PHANTOM_SKILL_FALLBACK = config.getBoolean("PhantomSkillFallback", true);
		PHANTOM_COMBAT_CONTROLLER = config.getBoolean("PhantomCombatController", true);
		PHANTOM_ARCHER_KITING = config.getBoolean("PhantomArcherKiting", true);
		PHANTOM_HUNTER_RETALIATE = config.getBoolean("PhantomHunterRetaliate", true);
		PHANTOM_PVP_ENABLED = config.getBoolean("PhantomPvpEnabled", true);
		PHANTOM_PVP_SELF_DEFENSE = config.getBoolean("PhantomPvpSelfDefense", true);
		PHANTOM_PVP_REACT_TO_FLAGGED = config.getBoolean("PhantomPvpReactToFlagged", true);
		PHANTOM_PVP_DUELS = config.getBoolean("PhantomPvpDuels", true);
		PHANTOM_PVP_PARTY_DEFENSE = config.getBoolean("PhantomPvpPartyDefense", true);
		PHANTOM_PVP_BETWEEN_PHANTOMS = config.getBoolean("PhantomPvpBetweenPhantoms", true);
		PHANTOM_PVP_OPEN_WORLD_GANK = config.getBoolean("PhantomPvpOpenWorldGank", false);
		PHANTOM_PVP_DEATH_DROPS = config.getBoolean("PhantomPvpDeathDrops", true);
		PHANTOM_PVP_MAX_LEVEL_GAP_ABOVE_PLAYER = config.getInt("PhantomPvpMaxLevelGapAbovePlayer", 6);
		PHANTOM_PVP_AGGRESSOR_PERCENT = config.getInt("PhantomPvpAggressorPercent", 15);
		PHANTOM_PVP_REACT_CHANCE_PERCENT = config.getInt("PhantomPvpReactChancePercent", 25);
		PHANTOM_PVP_RED_REACT_CHANCE_PERCENT = config.getInt("PhantomPvpRedReactChancePercent", 65);
		PHANTOM_PVP_RED_REACT_ALL = config.getBoolean("PhantomPvpRedReactAll", true);
		PHANTOM_PVP_ENGAGE_COOLDOWN_SECONDS = config.getInt("PhantomPvpEngageCooldownSeconds", 300);
		PHANTOM_PVP_CLAN_DEFENSE = config.getBoolean("PhantomPvpClanDefense", true);
		PHANTOM_PVP_DEFEND_RADIUS = config.getInt("PhantomPvpDefendRadius", 1200);
		PHANTOM_PVP_DUEL_CHANCE_PERCENT = config.getInt("PhantomPvpDuelChancePercent", 10);
		PHANTOM_PVP_FLEE_HP_PERCENT = config.getInt("PhantomPvpFleeHpPercent", 30);
		FAKE_PLAYER_AMBIENT_TRADE_INTERVAL_SECONDS = config.getInt("FakePlayerAmbientTradeIntervalSeconds", 90);
		FAKE_PLAYER_AMBIENT_SHOUT_INTERVAL_SECONDS = config.getInt("FakePlayerAmbientShoutIntervalSeconds", 120);
		FAKE_PLAYER_MAX_PUBLIC_CHATS_PER_MINUTE = config.getInt("FakePlayerMaxPublicChatsPerMinute", 8);
		FAKE_PLAYER_BOT_CHAT_CHAIN_DEPTH = Math.max(0, config.getInt("FakePlayerBotChatChainDepth", 2));
		FAKE_PLAYER_BOT_CHAT_REPLY_CHANCE = Math.min(100, Math.max(0, config.getInt("FakePlayerBotChatReplyChance", 15)));
		TRADE_AD_PARSER_V2 = config.getBoolean("TradeAdParserV2", true);
		TRADE_AD_LINKED_ITEMS = config.getBoolean("TradeAdLinkedItems", true);
		TRADE_AD_STATUS_REPLIES = config.getBoolean("TradeAdStatusReplies", true);
		TRADE_AD_FORMAT_HINT = config.getBoolean("TradeAdFormatHint", true);
		TRADE_AD_OFFERS_PER_MINUTE = Math.max(1, config.getInt("TradeAdOffersPerMinute", 60));
		TRADE_AD_OFFERS_PER_PLAYER_PER_MINUTE = Math.max(1, config.getInt("TradeAdOffersPerPlayerPerMinute", 4));
		TRADE_AD_REPLY_MIN_MS = Math.max(0, config.getInt("TradeAdReplyMinMs", 3000));
		TRADE_AD_REPLY_MAX_MS = Math.max(TRADE_AD_REPLY_MIN_MS, config.getInt("TradeAdReplyMaxMs", 7000));
		TRADE_AD_CLARIFY = config.getBoolean("TradeAdClarify", true);
		TRADE_AD_MAX_ITEMS = Math.max(1, Math.min(3, config.getInt("TradeAdMaxItems", 3)));
		TRADE_AD_ENCHANT_PRICING = config.getBoolean("TradeAdEnchantPricing", true);
		TRADE_OFFER_TIMEOUT_SECONDS = Math.max(30, config.getInt("TradeOfferTimeoutSeconds", 180));
	}
}
