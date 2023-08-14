package com.bgsoftware.ssbislandnpcs.npc.citizens;

import com.bgsoftware.ssbislandnpcs.SSBIslandNPCs;
import com.bgsoftware.ssbislandnpcs.config.NPCMetadata;
import com.bgsoftware.ssbislandnpcs.config.OnClickAction;
import com.bgsoftware.ssbislandnpcs.npc.IslandNPC;
import com.bgsoftware.ssbislandnpcs.npc.NPCProvider;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.stages.AbstractStage;
import fr.skytasul.quests.commands.CommandsPlayerManagement;
import fr.skytasul.quests.players.PlayerAccount;
import fr.skytasul.quests.players.PlayerQuestDatas;
import fr.skytasul.quests.players.PlayersManager;
import fr.skytasul.quests.stages.StageBringBack;
import fr.skytasul.quests.stages.StageNPC;
import fr.skytasul.quests.structure.BranchesManager;
import fr.skytasul.quests.structure.Quest;
import fr.skytasul.quests.structure.QuestBranch;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CitizensNPCProvider implements NPCProvider {

    private static final Pattern OWNER_PLACEHOLDER_PATTERN = Pattern.compile("%owner%");
    private static final Pattern PLAYER_PLACEHOLDER_PATTERN = Pattern.compile("%player%");
    private static final String NPC_METADATA_KEY = "SSBIslandsNPC_Metadata";

    private final SSBIslandNPCs module;
    private boolean canLoadData = false;

    private final NPCDataStore dataStore;
    private final NPCRegistry npcRegistry;

    private final Map<Player, NPC> playerNPC = new HashMap<>();

    private String npcName;

    private ConfigurationSection config;

    private String suffixeQuest;
    private String suffixeNPC;


    public CitizensNPCProvider(SSBIslandNPCs module) {
        this.module = module;
        Bukkit.getPluginManager().registerEvents(new ListenerImpl(), module.getPlugin());
        Bukkit.getScheduler().runTaskLater(module.getPlugin(), () -> {
            if (!canLoadData) {
                canLoadData = true;
                loadNPCs();
            }
        }, 5L);

        // We want our NPCs to not be saved into disk.
        this.dataStore = new MemoryNPCDataStore();
        this.npcRegistry = CitizensAPI.createNamedNPCRegistry("SSBIslandsNPC", this.dataStore);
    }

    @Override
    public IslandNPC createNPC(Island island, NPCMetadata metadata) {

        this.npcName = metadata.npcName;
        Matcher ownerPlaceholderMatcher = OWNER_PLACEHOLDER_PATTERN.matcher(npcName);
        if (ownerPlaceholderMatcher.find())
            npcName = ownerPlaceholderMatcher.replaceAll(island.getOwner().getName());

        NPC npc = this.npcRegistry.createNPC(metadata.npcType, island.getUniqueId(),
                this.dataStore.createUniqueNPCId(this.npcRegistry), npcName);

        npc.data().set(NPC_METADATA_KEY, metadata);

        if (!metadata.displayName.isEmpty()) {
            npc.data().setPersistent(NPC.NAMEPLATE_VISIBLE_METADATA, false);
        }

        if (metadata.lookAtNearby) {
            LookClose lookClose = npc.getOrAddTrait(LookClose.class);
            lookClose.toggle();
        }

        Location islandLocation = island.getCenter(module.getPlugin().getSettings().getWorlds().getDefaultWorld());
        npc.spawn(metadata.spawnOffset.applyToLocation(islandLocation));

        return new CitizensIslandNPC(npc);
    }

    @Override
    public void loadNPCs() {
        this.config = module.getConfig();
        this.suffixeQuest = config.getString("suffixe-quest");
        this.suffixeNPC = config.getString("suffixe-npc");
        if (!canLoadData)
            return;

        // We don't actually load the data for islands unless their chunk is loaded.
        module.getPlugin().getGrid().getIslands().forEach(island -> {
            NPC existingNPC = this.npcRegistry.getByUniqueId(island.getUniqueId());
            if (existingNPC != null)
                existingNPC.destroy();

            NPCMetadata npcMetadata = module.getSettings().schematics.get(island.getSchematicName().toLowerCase(Locale.ENGLISH));

            if (npcMetadata == null)
                return;

            Location spawnLocation = npcMetadata.spawnOffset.applyToLocation(island.getCenter(
                    module.getPlugin().getSettings().getWorlds().getDefaultWorld()));

            if (!spawnLocation.getWorld().isChunkLoaded(spawnLocation.getBlockX() >> 4, spawnLocation.getBlockZ() >> 4))
                return;

            // Only spawn the NPC if the chunk is loaded.
            module.getNPCHandler().createNPC(island, npcMetadata);
        });
    }

    @Override
    public void unloadNPCs() {
        this.npcRegistry.deregisterAll();
    }

    private class ListenerImpl implements Listener {

        @EventHandler
        public void onEnable(CitizensEnableEvent event) {
            canLoadData = true;
            loadNPCs();
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onNPCClick(NPCLeftClickEvent event) {

            Player player = event.getClicker();
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
            Island island = superiorPlayer.getIsland();
            SuperiorPlayer superiorPlayerOwner = superiorPlayer.getIslandLeader();

            if(event.getNPC().getName().equals(npcName) && superiorPlayer.equals(superiorPlayerOwner) && player.isSneaking()){

                player.sendMessage(suffixeNPC+config.getString("selectionNPC"));
                playerNPC.put(player,event.getNPC());

            } else if(event.getNPC().getName().equals(npcName)){
                List<Integer> liste = config.getIntegerList("quests");
                int compteur = 0;

                Quest quest = null;
                PlayerAccount playerAccount = PlayersManager.getPlayerAccount(player);

                while (compteur < liste.size()){
                    if(!QuestsAPI.getQuests().getQuest(compteur).hasFinished(playerAccount)){
                        quest = QuestsAPI.getQuests().getQuest(compteur);
                        break;
                    }
                    compteur ++;
                }
                if(quest != null){
                    onClickEvent(quest,player);
                } else {
                    player.sendMessage(suffixeQuest+config.getString("noMoreQuest"));
                }
            }


            NPCMetadata metadata = event.getNPC().data().get(NPC_METADATA_KEY);
            if (metadata != null && metadata.onLeftClickAction != null)
                handleNPCClick(metadata.onLeftClickAction, event.getClicker());
        }

        @EventHandler
        public void onSneakClick(PlayerInteractEvent e){
            Player p = e.getPlayer();
            Action a = e.getAction();
            if(playerNPC.containsKey(p)){
                if((a == Action.LEFT_CLICK_AIR && p.isSneaking()) || (a == Action.LEFT_CLICK_BLOCK && p.isSneaking())){
                    Location location = p.getLocation();
                    SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(p);
                    Island island = superiorPlayer.getIsland();
                    Island island1 = SuperiorSkyblockAPI.getIslandAt(location);
                    if(island.equals(island1)){
                        NPC npc = playerNPC.get(p);
                        npc.despawn();
                        npc.spawn(location);
                        playerNPC.remove(p);
                        p.sendMessage(suffixeNPC+config.getString("selectionNPCPlaced"));
                    } else {
                        p.sendMessage(suffixeNPC+config.getString("selectionInAnotherIsland"));
                    }
                } else if((a == Action.LEFT_CLICK_AIR) || (a == Action.LEFT_CLICK_BLOCK)){
                    p.sendMessage(suffixeNPC+config.getString("selectionCancel"));
                    playerNPC.remove(p);

                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onNPCClick(NPCRightClickEvent event) {

            if(event.getNPC().getName().equals(npcName)){

                List<Integer> liste = config.getIntegerList("quests");
                Player player = event.getClicker();
                int compteur = 0;

                Quest quest = null;
                PlayerAccount playerAccount = PlayersManager.getPlayerAccount(player);

                while (compteur < liste.size()){
                    if(!QuestsAPI.getQuests().getQuest(liste.get(compteur)).hasFinished(playerAccount)){
                        quest = QuestsAPI.getQuests().getQuest(liste.get(compteur));
                        break;
                    }
                    compteur ++;
                }
                if(quest != null){
                    onClickEvent(quest,player);
                } else {
                    player.sendMessage(suffixeQuest+config.getString("noMoreQuest"));
                }

            }


            NPCMetadata metadata = event.getNPC().data().get(NPC_METADATA_KEY);
            if (metadata != null && metadata.onRightClickAction != null)
                handleNPCClick(metadata.onRightClickAction, event.getClicker());
        }

        private void handleNPCClick(OnClickAction clickAction, Player clicker) {
            switch (clickAction.action) {
                case COMMAND:
                    List<String> commandsToExecute = (List<String>) clickAction.actionData;
                    if (!commandsToExecute.isEmpty())
                        commandsToExecute.forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                PLAYER_PLACEHOLDER_PATTERN.matcher(command).replaceAll(clicker.getName())));
                    break;
            }
        }

        private void onClickEvent(Quest quest, Player player){
            PlayerAccount playerAccount = PlayersManager.getPlayerAccount(player);
            Map<ItemStack, Integer> amountsMap = new HashMap<>();
            Boolean all = false;
            if(quest.hasStarted(playerAccount)){

                PlayerQuestDatas questData = playerAccount.getQuestDatas(quest);
                int num = questData.getStage();
                BranchesManager manager = quest.getBranchesManager();
                QuestBranch currentBranch = manager.getBranch(questData.getBranch());

                if(currentBranch.getRegularStage(num) instanceof StageBringBack stageBringBack){

                    Boolean test = stageBringBack.checkItems(player,true);
                    if(test){
                        stageBringBack.removeItems(player);
                        AbstractStage regularStage = quest.getBranchesManager().getPlayerBranch(playerAccount).getRegularStage(num);
                        if(!questData.isInEndingStages()){

                            currentBranch.finishStage(player,regularStage);
                        }
                    }


                } else if(currentBranch.getRegularStage(num) instanceof StageNPC stageNPC){
                    if(stageNPC.getNPC().getName().equals(npcName)){
                        AbstractStage regularStage = quest.getBranchesManager().getPlayerBranch(playerAccount).getRegularStage(num);
                        if(!questData.isInEndingStages()){
                            currentBranch.finishStage(player,regularStage);
                        }
                    }


                } else {
                    all = true;
                }
            } else {
                all = true;
            }

            if(all == true){
                quest.clickNPC(player);
            }
        }

    }

}
