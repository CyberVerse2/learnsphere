package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.SharingService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The defaults the sharing contract gives other implementations. */
class SharingServiceDefaultsTest {

    @Test
    void importAllowingAnOlderVersionFallsBackToThePlainImport() {
        List<String> calls = new ArrayList<>();
        JobUpdate queued = new JobUpdate("job", JobKind.IMPORT_PACKAGE, null, JobStatus.QUEUED, 0, null, 0, 0);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            calls.add(method.getName() + "/" + args.length);
            return queued;
        };
        SharingService service = (SharingService) Proxy.newProxyInstance(SharingService.class.getClassLoader(),
                new Class<?>[] {SharingService.class}, handler);

        assertThat(service.importCourse(Path.of("course.learnsphere"), null, true)).isSameAs(queued);
        assertThat(calls).containsExactly("importCourse/2");
    }

    @Test
    void packageOlderThanTheInstalledCopyNeedsConfirmation() {
        PackageInfo older = info(1, 2, false);
        PackageInfo newer = info(3, 2, false);
        PackageInfo fresh = info(1, null, false);
        PackageInfo own = info(1, 1, true);

        assertThat(older.canImport()).isFalse();
        assertThat(older.isOlderThanInstalled()).isTrue();
        assertThat(newer.canImport()).isTrue();
        assertThat(fresh.canImport()).isTrue();
        assertThat(own.canImport()).isFalse();
    }

    private static PackageInfo info(int version, Integer installed, boolean createdHere) {
        return new PackageInfo("6f1c3f0e-6d0a-4c39-9a52-3c6f4a1b2c3d", 1, "Course", null, null, 0, version, 0, 1, 1, 0, 10,
                false, installed == null ? null : "8b0c1a2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d", installed, createdHere);
    }
}
