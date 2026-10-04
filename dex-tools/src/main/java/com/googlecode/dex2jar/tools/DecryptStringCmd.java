package com.googlecode.dex2jar.tools;

import com.googlecode.dex2jar.tools.StringDecryptor.MethodConfig;
import com.googlecode.d2j.util.ArchiveIO;
import com.googlecode.dex2jar.tools.BaseCmd.Syntax;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.ClassNode;

@Syntax(cmd = "d2j-decrypt-string", syntax = "[options] <jar>", desc = "Decrypt in class file", onlineHelp = "https"
        + "://sourceforge.net/p/dex2jar/wiki/DecryptStrings\nhttps://bitbucket.org/pxb1988/dex2jar/wiki/DecryptStrings")
public class DecryptStringCmd extends BaseCmd {

    public static void main(String... args) {
        new DecryptStringCmd().doMain(args);
    }

    @Opt(opt = "f", longOpt = "force", hasArg = false, description = "force overwrite")
    private boolean forceOverwrite = false;

    @Opt(opt = "o", longOpt = "output", description = "output of .jar files, default is "
            + "$current_dir/[jar-name]-decrypted.jar", argName = "out")
    private Path output;

    @Opt(opt = "m", longOpt = "methods", description = "a file contain a list of methods, each line like: La/b;"
            + "->decrypt(III)Ljava/lang/String;", argName = "cfg")
    private Path method;

    @Opt(opt = "mo", longOpt = "decrypt-method-owner", description = "the owner of the method which can decrypt the "
            + "stings, example: java.lang.String", argName = "owner")
    private String methodOwner;

    @Opt(opt = "mn", longOpt = "decrypt-method-name", description = "the owner of the method which can decrypt the "
            + "stings, the method's signature must be static (parameter-type)Ljava/lang/String;. Please use -pt,"
            + "--parameter-type to set the argument decrypt.", argName = "name")
    private String methodName;

    @Opt(opt = "cp", longOpt = "classpath", description = "add extra lib to classpath", argName = "cp")
    private String classpath;

    //extended parameter option: e.g. '-t int,byte,string' to specify a routine such as decryptionRoutine(int a, byte
    // b, String c)
    @Opt(opt = "t", longOpt = "arg-types", description = "comma-separated list of types:boolean,byte,short,char,int,"
            + "long,float,double,string. Default is string", argName = "type")
    private String parameterJTypes;

    @Opt(opt = "pd", longOpt = "parameters-descriptor", description = "the descriptor for the method which can "
            + "decrypt the stings, example1: Ljava/lang/String; example2: III, default is Ljava/lang/String;",
            argName = "type")
    private String parametersDescriptor;

    @Opt(opt = "d", longOpt = "delete", hasArg = false, description = "delete the method which can decrypt the stings")
    private boolean deleteMethod = false;

    @Opt(opt = "da", longOpt = "deep-analyze", hasArg = false, description = "use dex2jar IR to static analyze and "
            + "find more values like byte[]")
    private boolean deepAnalyze = false;

    @Opt(opt = "v", longOpt = "verbose", hasArg = false, description = "show more on output")
    private boolean verbose = false;

    MethodConfig build(String line) {
        int idx = line.indexOf("->");
        if (idx < 0) {
            throw new RuntimeException("Can't read line:" + line);
        }
        String owner = line.substring(0, idx);

        if (owner.startsWith("L") && owner.endsWith(";")) {
            owner = owner.substring(1, owner.length() - 1);
        }

        int idx2 = line.indexOf('(', idx);
        if (idx2 < 0) {
            throw new RuntimeException("Can't read line:" + line);
        }

        String name = line.substring(idx + 2, idx2);

        String desc = line.substring(idx2);
        if (desc.endsWith(")")) {
            desc = desc + "Ljava/lang/String;";
        }

        MethodConfig config = new MethodConfig();
        config.owner = owner;
        config.desc = desc;
        config.name = name;
        return config;

    }

    @Override
    protected void doCommandLine() throws Exception {
        if (remainingArgs.length == 0) {
            throw new HelpException("One <jar> file is required");
        } else if (remainingArgs.length > 1) {
            throw new HelpException("Only one <jar> file is required, But we found " + remainingArgs.length);
        }

        final Path jar = new File(remainingArgs[0]).toPath();
        if (!Files.exists(jar)) {
            System.err.println(jar + " doesn't exist");
            return;
        }
        if (output == null) {
            if (Files.isDirectory(jar)) {
                output = new File(jar.getFileName() + "-decrypted.jar").toPath();
            } else {
                output = new File(getBaseName(jar.getFileName().toString()) + "-decrypted.jar").toPath();
            }
        }

        if (Files.exists(output) && !forceOverwrite) {
            System.err.println(output + " exists, use --force to overwrite");
            return;
        }

        System.err.println(jar + " -> " + output);

        List<MethodConfig> methodConfigs = collectMethodConfigs();
        if (methodConfigs == null || methodConfigs.isEmpty()) {
            System.err.println("No method selected !");
            return;
        }

        StringDecryptor decryptor = new StringDecryptor(deleteMethod, deepAnalyze, verbose);
        try (DecryptMethodLoader loader = new DecryptMethodLoader(jar, classpath)) {
            final Map<MethodConfig, MethodConfig> map = loader.loadMethods(methodConfigs);
            ArchiveIO.writeZip(output, outputBase -> {
                walkJarOrDir(jar, (file, relative) -> {
                    if (file.getFileName().toString().endsWith(".class")) {
                        Path dist1 = outputBase.resolve(relative);
                        createParentDirectories(dist1);
                        byte[] data = Files.readAllBytes(file);
                        ClassNode cn = decryptor.readClassNode(data);

                        if (decryptor.decrypt(cn, map)) {
                            byte[] data2 = decryptor.toByteArray(cn);
                            Files.write(dist1, data2);
                        } else {
                            Files.write(dist1, data);
                        }
                    } else {
                        Path dist1 = outputBase.resolve(relative);
                        createParentDirectories(dist1);
                        Files.copy(file, dist1);
                    }
                });
            });
        }
    }

    public static String v(Object[] values) { return StringDecryptor.v(values); }

    /** Collect methods from --methods and --method-owner,--method-name. */
    private List<MethodConfig> collectMethodConfigs() throws IOException {
        List<MethodConfig> methodConfigs = new ArrayList<>();
        if (this.method != null) {
            for (String line : Files.readAllLines(this.method, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                methodConfigs.add(this.build(line));
            }
        }
        if (methodOwner != null && methodName != null) {
            if (this.parametersDescriptor != null) {
                methodConfigs.add(this.build("L" + methodOwner.replace('.', '/') + ";->" + methodName + "("
                        + this.parametersDescriptor + ")Ljava/lang/String;"));
            } else if (this.parameterJTypes != null) {

                //parameterJTypes is a comma-separated list of the decryption method's parameters
                String[] typeList = parameterJTypes.split("[,;:]");
                //switch for all the supported types. String is default
                StringBuilder sb = new StringBuilder();
                for (String s : typeList) {
                    switch (s) {
                    case "boolean":
                        sb.append("Z");
                        break;
                    case "byte":
                        sb.append("B");
                        break;
                    case "short":
                        sb.append("S");
                        break;
                    case "char":
                        sb.append("C");
                        break;
                    case "int":
                        sb.append("I");
                        break;
                    case "long":
                        sb.append("J");
                        break;
                    case "float":
                        sb.append("F");
                        break;
                    case "double":
                        sb.append("D");
                        break;
                    case "string":
                        sb.append("Ljava/lang/String;");
                        break;

                    default:
                        throw new RuntimeException("not support type " + s + " on -t/--arg-types");
                    }
                }
                methodConfigs.add(this.build("L" + methodOwner.replace('.', '/') + ";->" + methodName + "("
                        + sb + ")Ljava/lang/String;"));
            } else {
                methodConfigs.add(this.build("L" + methodOwner.replace('.', '/') + ";->" + methodName + "(Ljava/lang"
                        + "/String;)Ljava/lang/String;"));
            }
        }
        return methodConfigs;
    }

}
