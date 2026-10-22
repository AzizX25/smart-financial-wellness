-- Preserve existing indicator-backed recommendations and immutable V21 scores.
ALTER TABLE recommendation_justification
    ALTER COLUMN evaluation_indicator_id DROP NOT NULL,
    ADD COLUMN evaluation_dimension_id UUID REFERENCES evaluation_dimension(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_recommendation_justification_single_source CHECK (
        NUM_NONNULLS(evaluation_indicator_id, evaluation_dimension_id) = 1
    ),
    ADD CONSTRAINT uk_recommendation_justification_dimension UNIQUE (
        recommendation_id, evaluation_dimension_id
    );

CREATE UNIQUE INDEX uk_rule_recommendation_evaluation_version
    ON personalized_recommendation(wellness_evaluation_id, recommendation_type, rules_version)
    WHERE generation_method = 'RULES';

CREATE OR REPLACE FUNCTION validate_recommendation_justification_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    recommendation_evaluation_id UUID;
    source_evaluation_id UUID;
    source_available BOOLEAN;
BEGIN
    SELECT wellness_evaluation_id INTO recommendation_evaluation_id
    FROM personalized_recommendation WHERE id = NEW.recommendation_id;

    IF NEW.evaluation_dimension_id IS NOT NULL THEN
        SELECT wellness_evaluation_id, available INTO source_evaluation_id, source_available
        FROM evaluation_dimension WHERE id = NEW.evaluation_dimension_id;
        IF source_available IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Recommendation requires an available score factor'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT dimension.wellness_evaluation_id INTO source_evaluation_id
        FROM evaluation_indicator indicator
        JOIN evaluation_dimension dimension ON dimension.id = indicator.evaluation_dimension_id
        WHERE indicator.id = NEW.evaluation_indicator_id;
    END IF;

    IF source_evaluation_id IS NULL
       OR recommendation_evaluation_id IS DISTINCT FROM source_evaluation_id THEN
        RAISE EXCEPTION 'Recommendation justification must belong to the same evaluation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;
