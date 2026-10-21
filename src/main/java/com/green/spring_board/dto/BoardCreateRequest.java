package com.green.spring_board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class BoardCreateRequest {
    @NotBlank
    @Size(min = 10, max = 50) // 10자 이상, 50자 이하
    private String title;

    @NotBlank
    @Size(min = 10) // 10자 이상
    private String content;
}
