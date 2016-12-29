package io.sls.core.tts;

import io.sls.core.lifecycle.AbstractLifecycleTask;
import io.sls.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.Data;
import io.sls.utilities.SecurityUtilities;

import javax.inject.Singleton;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Singleton
public class TextToSpeechTask extends AbstractLifecycleTask {
    private static final String OUTPUT_KEY = "output";
    private Map<String, URI> ttsResourceURIs = new HashMap<String, URI>();

    @Override
    public String getId() {
        return "io.sls.tts";  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Object getComponent() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getComponentDependencies() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getOutputDependencies() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void init() {
        //welcome
        ttsResourceURIs.put("dd300fb56baaa3a6b07442c19e2a39e9b0dcc938", URI.create("tts://io.sls.tts/fc00d65b594bf522a42a4477dd6c9b2d6ab0b4c6.mp3"));
        //greeting
        ttsResourceURIs.put("fe701619beda31c2b2bce7bd87b759c92e72a647", URI.create("tts://io.sls.tts/611dd41b36333e92bd0fe2a6a4b2fa9f60bbf204.mp3"));
        //slide 1.1
        ttsResourceURIs.put("a352b8e0e425be8857235de6de71aee736cd78f4", URI.create("tts://io.sls.tts/e41f5f2096c4d8e0fb1a309c68f24b2e552f2587.mp3"));
        //slide 1.2
        ttsResourceURIs.put("b4e5e0bc7eed287ac353e2cc804ad00e1241522a", URI.create("tts://io.sls.tts/4c4a80f963cc266dc4471ad29a7686e6719f32b4.mp3"));
        //slide 2.1
        ttsResourceURIs.put("892adaddb1ea2fe1d4ae87a12a06ff2ea214f790", URI.create("tts://io.sls.tts/c4646f209d5830797910e24206ffb83d7685fefe.mp3"));
        //slide 2.2
        ttsResourceURIs.put("28f2f7dcd22994da12624b1c629950c15c48de3b", URI.create("tts://io.sls.tts/117026245ac67b8c307634c48a31734592996604.mp3"));
        //slide 2.3
        ttsResourceURIs.put("2a5e80725f3d94b79abc0f6b0448e9381d4eb8ef", URI.create("tts://io.sls.tts/153e0b5d5811cf3681928fd421c40ca2e4a4b14b.mp3"));
        //slide 2.4
        ttsResourceURIs.put("936b08b41273fe9a001fd2e230313f3709dd6165", URI.create("tts://io.sls.tts/b831e5e3dc62fa488ddb3ec50bd75da355cdadcd.mp3"));
        //slide 2.5
        ttsResourceURIs.put("beff975e3d77b29640f732f9b88b140b0248c0ea", URI.create("tts://io.sls.tts/a41976780c041cb7832c01389ec3c4fcee91ddf4.mp3"));
        //slide 2.6
        ttsResourceURIs.put("c2467dd428ee2b58ecf91bead30e7a2ba04dd2b0", URI.create("tts://io.sls.tts/fb946f1bed6c3a42070ef994323502af9e0673f9.mp3"));
        //slide 2.7
        ttsResourceURIs.put("928b6c032099d98580516c82d61a4c2bd012a936", URI.create("tts://io.sls.tts/244f4f544816b9118aaad6f5d7f2e9153126560c.mp3"));
        //slide 2.8
        ttsResourceURIs.put("a20f4c13d759fec8aa3102bf03a55966e5fa6d4b", URI.create("tts://io.sls.tts/cfbf381d572e2dcc7824122e74362a56c99f3b92.mp3"));
        //slide 3.0
        ttsResourceURIs.put("732ef304b4aef23f3efd9e8f88222e6534646758", URI.create("tts://io.sls.tts/4b7105b05bf57972f49ecde522c6f331899b3ccd.mp3"));
        //slide 4.0
        ttsResourceURIs.put("f05c05ab6c119cb255c932473126d4063dffe06f", URI.create("tts://io.sls.tts/989d3d5c029889faf3077685c4d1a96aa4643848.mp3"));
        //slide 4.1
        ttsResourceURIs.put("325d7a5bfb7e46fc8e426fd224c753900c392bc4", URI.create("tts://io.sls.tts/e1bef281c52d7d833faf10a848e2cb84c3412234.mp3"));
        //slide 4.2
        ttsResourceURIs.put("eec785ef04ad3a036ab33237e21da6a474a550b3", URI.create("tts://io.sls.tts/48d839069a86d06d76735085471fadaf6bea1da8.mp3"));
        //slide 4.3
        ttsResourceURIs.put("e4dba849af897986d47f9a6a7d4d2cf555486bc9", URI.create("tts://io.sls.tts/81029a07bb3b559d81fdffbfb16af05fece6bf11.mp3"));
        //slide 4.4
        ttsResourceURIs.put("4cc80073d4b82057615d7dac9b21cb25c2194a94", URI.create("tts://io.sls.tts/24740ea688d7e6b242b81363f5686812f14916c4.mp3"));
        //slide 4.5
        ttsResourceURIs.put("a11ef1623b988a4ac8d7619183d73df15636cdbe", URI.create("tts://io.sls.tts/a11ef1623b988a4ac8d7619183d73df15636cdbe.mp3"));
        //slide 4.6
        ttsResourceURIs.put("45e680af45ab292a961103593f1f879254229371", URI.create("tts://io.sls.tts/191724e0d7357efa7ac3d8bd4aa40f8a837f788a.mp3"));
        //slide 4.7
        ttsResourceURIs.put("4a0ffdaecc8596f89d616b068ede950872962ff6", URI.create("tts://io.sls.tts/e3c0d12e2cc35bbb10efb2c69641db6161ad4268.mp3"));
        //slide 4.8
        ttsResourceURIs.put("b1842eeaaa78f940db1e8428dfba950307a1f1e7", URI.create("tts://io.sls.tts/8e145324b3b020bba26a98c2223d67c46525a06f.mp3"));
        //slide 4.9
        ttsResourceURIs.put("f45236084f91a24f352c9fe3c164b7d039169745", URI.create("tts://io.sls.tts/e692d08f5fc511419685f029e74d964cfe81578c.mp3"));
        //slide 5.0
        ttsResourceURIs.put("0075ccf60d00e5b1e0de22236707f2f5c7a06771", URI.create("tts://io.sls.tts/0da971a0d91c5d2137d116e5a1eaf873a6dc8a98.mp3"));
        //slide 5.1
        ttsResourceURIs.put("877ebda4105c1baf1e510dc4d93d8e5f128098b0", URI.create("tts://io.sls.tts/713def5e1446b0149ff5c29f187c98ae14320f3b.mp3"));
        //slide 5.2
        ttsResourceURIs.put("aa4aef97eb8b2560364e4dc4ededabda02d12a84", URI.create("tts://io.sls.tts/8b9996eccfa1bc8e2c7d9552c6a5532640188024.mp3"));
        //slide 5.3
        ttsResourceURIs.put("2a3a1877eefb2c4bcf923d08565dd8eddbe165fc", URI.create("tts://io.sls.tts/e9a78923d5506258f1a02eded8d81379ab1e129a.mp3"));
        //slide 5.4
        ttsResourceURIs.put("ec56429dd7e4df0e6781c42f20dff0aa2eaaf1a2", URI.create("tts://io.sls.tts/76083548777bda711acd40902a220b76269ead14.mp3"));
        //slide 5.5
        ttsResourceURIs.put("7ce7f518faf0707ffe3a4b1914edd81d47548d18", URI.create("tts://io.sls.tts/057acf30d18815923d33bc6b0384b99eaf714437.mp3"));
        //slide 5.6
        ttsResourceURIs.put("5a0e99469a405fa3224a4b831a6cfb52be283ce3", URI.create("tts://io.sls.tts/ba00b09cc251686df568d39bc455c430e306bc16.mp3"));
        //slide 5.7
        ttsResourceURIs.put("9dd642866782d23afe14cf9145e1e8eee4299b24", URI.create("tts://io.sls.tts/8654b8c42378740d307dec5d7b9c3415dbfecacd.mp3"));
        //slide 6.0
        ttsResourceURIs.put("9b4e9907d5105c24784415b350c03a81b100aed1", URI.create("tts://io.sls.tts/d2bfd9a7b8cebfc9f69e42ac62d28c8cda2cf403.mp3"));
        //slide 6.1
        ttsResourceURIs.put("0121988feaad36060ecde164f91df9c28dec8d36", URI.create("tts://io.sls.tts/807a21bfc87f2b7476bb3c26fb64fe69486289de.mp3"));
        //slide 6.2
        ttsResourceURIs.put("447faa5d0a91813d119b0b6278806bfc6290d710", URI.create("tts://io.sls.tts/67dcd7260e878de298008715980be58d4007e138.mp3"));
        //slide 6.3
        ttsResourceURIs.put("c700d773e445dee075aecde9a5d1fa95f687e9a2", URI.create("tts://io.sls.tts/9ab8d4c3863e74df72ab7f20fce053a9d093628e.mp3"));
        //slide 6.4
        ttsResourceURIs.put("4b9cbd8f1c654b3842da2e414d57c1d09ac50817", URI.create("tts://io.sls.tts/b77c5c27ba57b4849dd2f5cc733cda4e636c7210.mp3"));
        //slide 6.5
        ttsResourceURIs.put("ac3d1796380b8c65c28f7fef3e24dc7003c84835", URI.create("tts://io.sls.tts/8167941462cc118f335aeee8071cf349cba1a189.mp3"));
        //slide 6.6
        ttsResourceURIs.put("e3f57f5e0a7ac73985710c0a20ab9e65bb7de0aa", URI.create("tts://io.sls.tts/4d0177957780b0c29388f9178a65f0f1fca46fb9.mp3"));
        //slide 7.0
        ttsResourceURIs.put("5236fe42418cb6254a8077bc6c62465753e5cc52", URI.create("tts://io.sls.tts/b1484bafef9bd7fb65dbfc25a1cfc4316a8bf368.mp3"));
        //slide 7.1
        ttsResourceURIs.put("a8b79cc22df6affeec6b7090029358147bd01a4d", URI.create("tts://io.sls.tts/f01aa2b49d61b6ff024e93fc3ef178077984eeb4.mp3"));
        //slide 7.2
        ttsResourceURIs.put("df6077bd597a1259d30001defb460d2c049a00b2", URI.create("tts://io.sls.tts/63c609e9570d524b05f414a60c7df10d5ef4c2a8.mp3"));
        //slide 7.3
        ttsResourceURIs.put("5a297c45bea78da186166c3ad4dc4d30bb35117e", URI.create("tts://io.sls.tts/9e102d446f174507e0b6ba63c03c1435a0290c66.mp3"));
        //slide 7.4
        ttsResourceURIs.put("dc910635f2cb8b631abe3c5b75b574e7f4a0fdc6", URI.create("tts://io.sls.tts/d307d3cf0f3e879303c5738ed702b966e76e9d03.mp3"));
        //slide 8.0
        ttsResourceURIs.put("0079bdfa94020d28609d0978965614b0314abb2d", URI.create("tts://io.sls.tts/f92c7f7062c3ec7693f8171188066241b6c71c40.mp3"));
        //slide 8.1
        ttsResourceURIs.put("d29537accdc1fdfabb67b3a1cc53cdad99546e06", URI.create("tts://io.sls.tts/8d0a53f384ccdfcda47b722c647ae3a0826d3947.mp3"));
        //slide 8.2
        ttsResourceURIs.put("885cc4126324911f7c80dbe3e66ffb85a34f5148", URI.create("tts://io.sls.tts/e72106bfd199c5f09c1ea8ad8874c9ca85b6a340.mp3"));
        //I don't have an answer to your question at the moment. But if you give me your email address, I will ask a colleague and get back to you!
        ttsResourceURIs.put("106679d74609442e4a447f4b2b20cd5ecd53b71b", URI.create("tts://io.sls.tts/c958db3e2c5830bec91861250f5a71364d9eab47.mp3"));
        //I appreciate your trust in me, I'll get back to you soon, I promise!
        ttsResourceURIs.put("b88a9e49dd401a282477de801f1a6b1719847ec8", URI.create("tts://io.sls.tts/a119d03f74f6010323488a9fea2a5fdeb9bd415f.mp3"));
        //In case you have any other questions, don't hesitate to ask me.
        ttsResourceURIs.put("a8988fd6d616f824f250e0709a3f313f4632d6af", URI.create("tts://io.sls.tts/3e0083ce30284df102c3beb42d7f3adc11d5e0b2.mp3"));
        //Bye bye, see you soon!
        ttsResourceURIs.put("d262a9f6d8998624c5f23bdc6070c51101ac77cc", URI.create("tts://io.sls.tts/09c1655cc614905a9675afb930453cb3f53d9c25.mp3"));

    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData latestData = memory.getCurrentStep().getLatestData(OUTPUT_KEY);
        if (latestData == null) {
            return;
        }
        String output = (String) latestData.getResult();

        String sha1Hash = null;
        try {
            sha1Hash = SecurityUtilities.calculateSHA1(output);
        } catch (UnsupportedEncodingException e) {
            throw new LifecycleException("Error while calculating SHA-1 for TTS matching.");
        } catch (NoSuchAlgorithmException e) {
            throw new LifecycleException("Error while calculating SHA-1 for TTS matching.");
        }

        // tts
        if (ttsResourceURIs.containsKey(sha1Hash)) {
            Data ttsData = new Data("tts", ttsResourceURIs.get(sha1Hash));
            ttsData.setPublic(true);
            memory.getCurrentStep().storeData(ttsData);
        }
    }
}
