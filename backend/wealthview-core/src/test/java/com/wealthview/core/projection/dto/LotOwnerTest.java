package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.PersonId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LotOwnerTest {

    @Test
    void fromString_joint_returnsJoint() {
        assertThat(LotOwner.fromString("joint")).isEqualTo(LotOwner.JOINT);
    }

    @Test
    void fromString_primary_returnsPrimary() {
        assertThat(LotOwner.fromString("primary")).isEqualTo(LotOwner.PRIMARY);
    }

    @Test
    void fromString_spouse_returnsSpouse() {
        assertThat(LotOwner.fromString("spouse")).isEqualTo(LotOwner.SPOUSE);
    }

    @Test
    void fromString_upperCase_isCaseInsensitive() {
        assertThat(LotOwner.fromString("SPOUSE")).isEqualTo(LotOwner.SPOUSE);
    }

    @Test
    void fromString_null_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> LotOwner.fromString(null));
    }

    @Test
    void fromString_unknown_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> LotOwner.fromString("garbage"));
    }

    @Test
    void fromCode_decodesOrdinal() {
        for (LotOwner owner : LotOwner.values()) {
            assertThat(LotOwner.fromCode(owner.ordinal())).isEqualTo(owner);
        }
    }

    @Test
    void forPerson_primary_returnsPrimary() {
        assertThat(LotOwner.forPerson(PersonId.PRIMARY)).isEqualTo(LotOwner.PRIMARY);
    }

    @Test
    void forPerson_spouse_returnsSpouse() {
        assertThat(LotOwner.forPerson(PersonId.SPOUSE)).isEqualTo(LotOwner.SPOUSE);
    }
}
