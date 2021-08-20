import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import {
  GREY_COLOR,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR2,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';
import SearchIcon from '@material-ui/icons/Search';
import { pageEnum } from '../pages/pageEnum';

const useStyles = makeStyles({
  filter: {
    display: 'flex',
  },
  searchBox: {
    backgroundColor: 'transparent',
    border: `1px solid ${WHITE_COLOR}`,
    borderRadius: '4px',
    display: 'flex',
    height: '34px',
    marginTop: '7px',
    paddingLeft: '5px',
    width: '200px',
    alignItems: 'center',
  },
  searchBoxIcon: {
    height: '20px',
    marginLeft: '5px',
    color: WHITE_COLOR,
  },
  searchBoxInput: {
    '&:focus': {
      outline: 'none',
    },
    backgroundColor: 'transparent',
    border: 'none',
    fontSize: '13px',
    marginLeft: '10px',
    width: '150px',
    color: WHITE_COLOR,
    '&::placeholder': {
      color: LIGHT_GREY_COLOR,
    },
  },
});

interface IProps {
  page?: pageEnum;
  placeholder?: string;
  filter(text: string): void;
}

function getSearchName(page: pageEnum) {
  if (page === pageEnum.httpCalls) {
    return 'HTTP calls';
  } else if (page === pageEnum.gitCalls) {
    return 'Git calls';
  } else {
    return pageEnum[page];
  }
}

const FilterComponent = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.filter}>
      <div className={classes.searchBox}>
        <SearchIcon fontSize="large" className={classes.searchBoxIcon} />
        <input
          type={'text'}
          placeholder={
            props.page ? `Find ${getSearchName(props.page)}` : props.placeholder
          }
          className={classes.searchBoxInput}
          onChange={(f) => props.filter(f.target.value)}
        />
      </div>
    </div>
  );
};

export default FilterComponent;
