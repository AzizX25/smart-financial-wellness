package com.azizx25.smartfinancialwellness.goal;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class GoalException extends ApiException {

    private GoalException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static GoalException notFound() {
        return new GoalException(HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", "Goal not found");
    }

    public static GoalException invalid(String message) {
        return new GoalException(HttpStatus.BAD_REQUEST, "INVALID_GOAL", message);
    }

    public static GoalException conflict(String message) {
        return new GoalException(HttpStatus.CONFLICT, "GOAL_CONFLICT", message);
    }

    public static GoalException readOnly() {
        return conflict("Cancelled and archived goals are read-only");
    }

    public static GoalException invalidTransition() {
        return conflict("The requested goal status transition is not allowed");
    }

    public static GoalException contributionNotFound() {
        return new GoalException(
                HttpStatus.NOT_FOUND,
                "GOAL_CONTRIBUTION_NOT_FOUND",
                "Goal contribution not found");
    }

    public static GoalException allocationPlanRequired() {
        return new GoalException(
                HttpStatus.CONFLICT,
                "GOAL_ALLOCATION_PLAN_REQUIRED",
                "A Salary Plan is required for this month and currency");
    }

    public static GoalException pastAllocationReadOnly() {
        return new GoalException(
                HttpStatus.CONFLICT,
                "GOAL_ALLOCATION_PAST_MONTH",
                "Past goal allocations are read-only");
    }

    public static GoalException invalidAllocation(String message) {
        return new GoalException(HttpStatus.BAD_REQUEST, "INVALID_GOAL_ALLOCATION", message);
    }

    public static GoalException allocationCapacityExceeded() {
        return new GoalException(
                HttpStatus.CONFLICT,
                "GOAL_ALLOCATION_CAPACITY_EXCEEDED",
                "Goal allocations exceed the available saving capacity");
    }

    public static GoalException allocationConflict() {
        return new GoalException(
                HttpStatus.CONFLICT,
                "GOAL_ALLOCATION_CONFLICT",
                "Goal allocations could not be replaced because their data changed or is constrained");
    }
}
