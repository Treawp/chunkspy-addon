package com.egaganteng.chunkspy;

import com.egaganteng.chunkspy.modules.OrientationChunkFinder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkSpyAddon extends MeteorAddon {

    public static final Logger LOG = LoggerFactory.getLogger("ChunkSpy");
    public static final String NAME = "ChunkSpy";

    @Override
    public void onInitialize() {
        LOG.info("ChunkSpy Addon Loaded");
        Modules.get().add(new OrientationChunkFinder());
    }

    @Override
    public String getPackage() {
        return "com.egaganteng.chunkspy";
    }
}
