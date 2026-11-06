package io.github.syldegil.autotunnel;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** User copies in private no_backup; no profile secrets or paths supplied by the UI. */
final class GraphLibrary {
    private final File dir;
    GraphLibrary(Context c){dir=new File(c.getNoBackupFilesDir(),"graph-library");}
    List<String> names(){String[] names=dir.list((d,n)->n.matches("copy-[a-f0-9-]{36}\\.bin"));if(names==null)return Collections.emptyList();Arrays.sort(names);return Arrays.asList(names);}
    void duplicate(GraphModel graph)throws IOException{
        if(names().size()>=32)throw new IOException("Bibliothèque limitée à32copies");
        if(!dir.isDirectory()&&!dir.mkdir())throw new IOException("Bibliothèque inaccessible");
        AtomicFile f=new AtomicFile(new File(dir,"copy-"+UUID.randomUUID()+".bin"));byte[] data=GraphCodec.encode(graph);FileOutputStream out=f.startWrite();try{out.write(data);f.finishWrite(out);}catch(IOException e){f.failWrite(out);throw e;}
    }
    GraphModel load(String name)throws IOException{
        if(!names().contains(name))throw new IOException("Copie inconnue");
        try(InputStream in=new FileInputStream(new File(dir,name));ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>GraphCodec.MAX_BYTES)throw new IOException("Copie trop grande");bytes.write(b,0,n);}return GraphCodec.decode(bytes.toByteArray());
        }
    }
}
