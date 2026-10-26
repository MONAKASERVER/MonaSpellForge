package net.monacraft.spellforge.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitLibrary;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;
import java.util.UUID;
import java.time.Instant;

public final class MonaSpellForgeClient implements ClientModInitializer {
    private static final Gson GSON=new GsonBuilder().registerTypeAdapter(Instant.class,(com.google.gson.JsonSerializer<Instant>)(value,type,context)->new com.google.gson.JsonPrimitive(value.toEpochMilli())).registerTypeAdapter(Instant.class,(com.google.gson.JsonDeserializer<Instant>)(json,type,context)->Instant.ofEpochMilli(json.getAsLong())).create();
    @Override public void onInitializeClient(){
        PayloadTypeRegistry.serverboundPlay().register(BridgePayload.TYPE,BridgePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BridgePayload.TYPE,BridgePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE,(payload,context)->context.client().execute(()->receive(context.client(),payload.data())));
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->send(new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.HELLO,UUID.randomUUID(),"",null,0,"fabric-26.1.2")));
    }
    private static void receive(net.minecraft.client.Minecraft client,byte[] data){
        try{
            var frame=SpellForgeProtocol.decode(data);
            if(frame.type()==SpellForgeProtocol.Type.OPEN_EDITOR){
                Circuit circuit=GSON.fromJson(frame.payload(),Circuit.class);
                client.setScreen(new SpellForgeScreen(client.screen,circuit,frame.sessionToken()));
            }else if(frame.type()==SpellForgeProtocol.Type.CIRCUIT_LIBRARY){
                CircuitLibrary library=GSON.fromJson(frame.payload(),CircuitLibrary.class);
                net.minecraft.client.gui.screens.Screen parent=client.screen;
                if(parent instanceof SpellLibraryScreen screen)parent=screen.parentScreen();
                else if(parent instanceof SpellForgeScreen screen)parent=screen.parentScreen();
                client.setScreen(new SpellLibraryScreen(parent,library,frame.sessionToken()));
            }else if(client.screen instanceof SpellForgeScreen screen)screen.accept(frame);
            else if(client.screen instanceof SpellLibraryScreen screen)screen.accept(frame);
        }catch(Exception ignored){}
    }
    static void send(SpellForgeProtocol.Frame frame){if(ClientPlayNetworking.canSend(BridgePayload.TYPE))ClientPlayNetworking.send(new BridgePayload(SpellForgeProtocol.encode(frame)));}
    static String json(Object object){return GSON.toJson(object);}
    static <T> T fromJson(String json,Class<T> type){return GSON.fromJson(json,type);}
}
