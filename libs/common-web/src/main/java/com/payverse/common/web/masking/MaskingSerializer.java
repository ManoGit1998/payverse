package com.payverse.common.web.masking;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.std.StdSerializer;

/** Jackson 3 serializer behind {@link Masked}. */
public class MaskingSerializer extends StdSerializer<String> {

    private final MaskType type;

    public MaskingSerializer() {
        this(MaskType.FULL);
    }

    private MaskingSerializer(MaskType type) {
        super(String.class);
        this.type = type;
    }

    @Override
    public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property) {
        Masked masked = property != null ? property.getAnnotation(Masked.class) : null;
        return masked != null ? new MaskingSerializer(masked.value()) : this;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(PiiMasker.mask(value, type));
    }
}
