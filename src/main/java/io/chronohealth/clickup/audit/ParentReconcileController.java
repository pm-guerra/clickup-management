package io.chronohealth.clickup.audit;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brings every parent in step with its subtasks (see {@link ParentReconciler}). Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/parents")
public class ParentReconcileController {

    private final ParentReconciler reconciler;

    public ParentReconcileController(ParentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    /**
     * Dry run by default; {@code apply=true} moves the parents.
     */
    @PostMapping("/reconcile")
    public ParentReconciler.Result reconcile(@RequestParam(defaultValue = "false") boolean apply) {
        return reconciler.reconcile(apply);
    }
}
