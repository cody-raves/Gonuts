package dev.doughbay.fabric.automation;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.core.execution.ExecutionResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StaffTransferHoldTest {
 @Test void ordinaryAndRecoveryResumeAreBlockedUntilEscapeReleasesItsHold(){var c=new AutomationSessionController(new AutomatedExecutionDriver());c.holdForStaffTransfer(true);assertTrue(c.resume().detail().contains("region transfer"));assertTrue(c.resumeFromPlayer().detail().contains("region transfer"));assertTrue(c.resumeRecoveredSession(null).detail().contains("region transfer"));c.holdForStaffTransfer(false);assertFalse(c.resume().detail().contains("region transfer"));}
 @Test void escapeCannotResumeAnUnrelatedStoppedOrRecoveryState(){var c=new AutomationSessionController(new AutomatedExecutionDriver());assertEquals(ExecutionResult.Outcome.REFUSED,c.resumeAfterStaffTransfer().outcome());assertTrue(c.resumeAfterStaffTransfer().detail().contains("preserving"));}
}
