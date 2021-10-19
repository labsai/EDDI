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
import { historyPush } from '../../../scripts/history';

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
    cursor: 'pointer',
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

  const urlSearchParams = new URLSearchParams(location.search);
  const searchValue = urlSearchParams.get('search');
  const pluginType = urlSearchParams.get('type');

  const [value, setValue] = React.useState(searchValue || '');

  const getQueryParams = () => {
    const params = [];
    const typeQuery = pluginType ? `type=${pluginType}` : undefined;
    const searchQuery = value.length ? `search=${value}` : undefined;
    if (typeQuery) {
      params.push(typeQuery);
    }
    if (searchQuery) {
      params.push(searchQuery);
    }

    return params;
  };

  const handleEnter = (e) => {
    if (e.key === 'Enter') {
      historyPush(location.pathname, getQueryParams());
      props.filter(value);
    }
  };

  const handleFilter = () => {
    historyPush(location.pathname, getQueryParams());
    props.filter(value);
  };

  React.useEffect(() => {
    if (value?.length) {
      props.filter(value);
    }
  }, []);

  return (
    <div className={classes.filter}>
      <div className={classes.searchBox}>
        <SearchIcon
          fontSize="large"
          className={classes.searchBoxIcon}
          onClick={handleFilter}
        />
        <input
          type={'text'}
          placeholder={
            props.page ? `Find ${getSearchName(props.page)}` : props.placeholder
          }
          value={value}
          className={classes.searchBoxInput}
          onChange={(f) => setValue(f.target.value)}
          onKeyUp={handleEnter}
        />
      </div>
    </div>
  );
};

export default FilterComponent;
