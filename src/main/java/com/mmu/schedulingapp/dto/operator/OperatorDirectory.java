package com.mmu.schedulingapp.dto.operator;
import java.util.List;
public record OperatorDirectory(List<OperatorItem> operators,int page,int size,long totalElements,int totalPages) { }
