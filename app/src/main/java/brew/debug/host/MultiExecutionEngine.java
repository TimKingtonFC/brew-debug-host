package brew.debug.host;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class MultiExecutionEngine extends ExecutionEngine {
    private Map<String, ExecutionEngine> enginesByFileExtension = new HashMap<>();

    public void register(String fileExtension, ExecutionEngine engine) {
        enginesByFileExtension.put(fileExtension, engine);
    }

    private ExecutionEngine getEngine(File file) {
        // TODO: Generalize
        if (file.isDirectory()) {
            return enginesByFileExtension.get("brw");
        }

        String filename = file.getName();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) {
            throw new RuntimeException("File has no extension: " + filename);
        }

        String extension = filename.substring(dotIndex + 1);
        ExecutionEngine engine = enginesByFileExtension.get(extension);
        if (engine == null) {
            throw new RuntimeException("No execution engine registered for extension " + extension);
        }

        return engine;
    }

    @Override
    public void compileFile(File file) {
        getEngine(file).compileFile(file);
    }

    @Override
    public IFunction resolveFunction(File file, int lineNumber) {
        return getEngine(file).resolveFunction(file, lineNumber);
    }

    @Override
    public int resolveInstructionReference(String instructionReference, int offset) {
        // Instruction references must start with the file extension.
        String type = instructionReference.substring(0, instructionReference.indexOf(":"));
        return enginesByFileExtension.get(type).resolveInstructionReference(instructionReference, offset);
    }

    @Override
    public IThread startThread(File file, String entryPoint, Console console) {
        return getEngine(file).startThread(file, entryPoint, console);
    }

    @Override
    public boolean isInternalFile(File file) {
        return getEngine(file).isInternalFile(file);
    }

}
