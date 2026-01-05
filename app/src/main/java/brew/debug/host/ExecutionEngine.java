package brew.debug.host;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.NotImplementedException;

public abstract class ExecutionEngine {

    public abstract IFunction resolveFunction(File file, int lineNumber);

    public abstract IThread startThread(File file, String entryPoint, Console console);

    public void compileFile(File file) {
        // TODO: Handle debug exceptions better
        try (Scanner in = new Scanner(file)) {
            compileFile(file, in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void compileFile(File file, String code) {
        compileFile(file, new Scanner(code));
    }

    private void compileFile(File file, Scanner in) {
        List<String> lines = new ArrayList<>();
        while (in.hasNextLine()) {
            lines.add(in.nextLine());
        }
        compileFile(file, lines);
    }

    public void compileFile(File file, List<String> lines) {
        throw new NotImplementedException("implement this, or override the other compile methods");
    }

    public boolean isInternalFile(File file) {
        return false;
    }
}
