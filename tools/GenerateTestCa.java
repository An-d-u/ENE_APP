import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 기기 시험 APK에 공개 CA만 넣는다. 개인키는 빌드 중 생성한 임시 폴더에서 즉시 지운다. */
class GenerateTestCa {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempDirectory("ene-instrumentation-ca-");
        Path store = temporary.resolve("ca.p12");
        String password = UUID.randomUUID().toString();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        try {
            run(keytool, List.of("-genkeypair", "-alias", "ca", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=ENE CA 00000000-0000-4000-8000-000000000001", "-startdate", "-5M", "-validity", "3650",
                "-ext", "BC:critical=ca:true,pathlen:0", "-ext", "KU:critical=keyCertSign", "-noprompt"));
            run(keytool, List.of("-exportcert", "-alias", "ca", "-keystore", store.toString(), "-storepass", password,
                "-file", output.toString()));
        } finally {
            Files.deleteIfExists(store);
            Files.deleteIfExists(temporary);
        }
    }

    private static void run(Path keytool, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("시험 인증서 생성 실패");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        }
    }
}
