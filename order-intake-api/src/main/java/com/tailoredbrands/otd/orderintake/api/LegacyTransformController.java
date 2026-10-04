package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.orderintake.soap.LegacyOrderTransformer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer helper: run the legacy-XML to canonical-JSON XSLT without creating an order.
 * Handy from Postman / SoapUI when working on the mapping.
 */
@RestController
@RequestMapping("/v1/legacy")
@Tag(name = "Legacy", description = "Legacy OMS XML tooling")
public class LegacyTransformController {

    private final LegacyOrderTransformer transformer;

    public LegacyTransformController(LegacyOrderTransformer transformer) {
        this.transformer = transformer;
    }

    @PostMapping(path = "/transform",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transform legacy order XML into the canonical Order JSON (XSLT only, nothing is stored)")
    public String transform(@RequestBody String legacyXml) {
        return transformer.toCanonicalJson(legacyXml);
    }
}
