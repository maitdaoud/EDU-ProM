package org.eduprom.miners.adaptiveNoise.conformance;

import org.deckfour.xes.model.XLog;
import org.eduprom.conformance.IConformanceObject;
import org.eduprom.miners.adaptiveNoise.IntermediateMiners.MiningResult;
import org.eduprom.miners.adaptiveNoise.IntermediateMiners.NoiseInductiveMiner;

import java.util.UUID;

public interface IAdaptiveNoiseConformanceObject extends IConformanceObject {

    UUID getId();

    XLog getLog();

    NoiseInductiveMiner getMiner();

    void setMiningResult(MiningResult result);

    MiningResult getMiningResult();

    void setConformanceInfo(ConformanceInfo conformanceInfo);
}
