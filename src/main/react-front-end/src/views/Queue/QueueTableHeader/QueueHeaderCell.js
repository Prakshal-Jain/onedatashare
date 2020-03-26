import TableCell from "@material-ui/core/TableCell";
import Tooltip from "@material-ui/core/Tooltip";
import TableSortLabel from "@material-ui/core/TableSortLabel";
import TableRow from "@material-ui/core/TableRow";
import React from "react";

const QueueHeaderCell = ({
                             handleRequestSort,
                             order,
                             orderBy,
                             sortKey,
                             title
                         }) => {
    return (
        <Tooltip title={"Sort by" + title} placement='bottom-end'>
            <TableCell className="QueueHeaderCell">
                <TableSortLabel
                    active={orderBy === sortKey}
                    direction={order}
                    onClick={() => {handleRequestSort(sortKey)}}>
                    <p>{title}</p>
                </TableSortLabel>
            </TableCell>
        </Tooltip>
    );
};

export default QueueHeaderCell;