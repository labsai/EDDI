import FormControl from '@material-ui/core/FormControl';
import MenuItem from '@material-ui/core/MenuItem';
import OutlinedInput from '@material-ui/core/OutlinedInput';
import Select from '@material-ui/core/Select';
import { makeStyles } from '@material-ui/core/styles';
import ArrowDropDownIcon from '@material-ui/icons/ArrowDropDown';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { RESOURCES } from '../../constants/paths';
import {
  BLUE_COLOR,
  BLUE_COLOR_TRANSPARENT,
  WHITE_COLOR,
  GREY_COLOR,
  LIGHT_BLUE_COLOR,
  LIGHT_GREY_COLOR2,
} from '../../../styles/DefaultStylingProperties';
import { historyPush } from '../../history';
import { pageEnum } from '../pages/pageEnum';

const useStyles = makeStyles({
  selectContainer: {
    width: 170,
    height: 39,
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,

    '&:hover': {
      borderBottom: `3px solid ${BLUE_COLOR}`,

      '& .MuiOutlinedInput-root': {
        color: BLUE_COLOR,
      },
    },
    '& .MuiOutlinedInput-root': {
      fontSize: '1.4rem',
      lineHeight: '2rem',
      height: '100%',
      display: 'flex',
      alignItems: 'flex-end',
      color: GREY_COLOR,
    },
    '& .MuiSelect-icon': {
      top: 'calc(50% - 8px)',
    },
    '& .MuiOutlinedInput-root .MuiOutlinedInput-notchedOutline': {
      borderColor: 'transparent',
    },
  },
  active: {
    '& .MuiOutlinedInput-root': {
      color: WHITE_COLOR,
    },
    borderBottom: `3px solid ${BLUE_COLOR}`,
  },
  input: {
    '& .MuiOutlinedInput-inputMarginDense': {
      paddingTop: 5,
      paddingBottom: 5,
      backgroundColor: 'transparent',
    },
  },
  select: {
    '& li': {
      fontSize: '1.4rem',
    },
    '& li:hover': {
      backgroundColor: BLUE_COLOR_TRANSPARENT,
    },
    '& .Mui-selected': {
      backgroundColor: LIGHT_BLUE_COLOR,
    },
    '& .Mui-disabled': {
      backgroundColor: 'transparent',
    },
    '& .Mui-selected:hover': {
      backgroundColor: LIGHT_BLUE_COLOR,
    },
  },
  arrowDropdown: {
    marginTop: 'auto',
    marginBottom: 'auto',
  },
});

const pluginResourceOptions = [
  'Regular dictionaries',
  'Behavior rules',
  'Output sets',
  'HTTP calls',
  'Git calls',
  'Properties',
];

interface IProps {
  page: pageEnum;
}

interface IOption {
  value: number;
  label: string;
}

const PluginSelectComponent = ({ page }: IProps) => {
  const classes = useStyles();
  const [selectedOption, setSelectedOption] = React.useState<IOption>({
    label: 'Resources',
    value: -1,
  });

  const isPluginPage = (): boolean => {
    return [
      pageEnum.dictionary,
      pageEnum.behavior,
      pageEnum.output,
      pageEnum.httpCalls,
      pageEnum.gitCalls,
      pageEnum.property,
    ].includes(page);
  };

  const setOption = () => {
    if (isPluginPage()) {
      setSelectedOption({ label: pluginResourceOptions[page], value: page });
    } else {
      setSelectedOption({ label: 'Resources', value: -1 });
    }
  };

  React.useEffect(() => {
    setOption();
  }, [page]);

  const handleSelect = (
    event: React.ChangeEvent<{
      value: number;
    }>,
  ) => {
    const value = event?.target?.value;
    if (typeof value === 'number') {
      const selectedOption = pluginResourceOptions[value];
      if (!selectedOption) {
        return;
      }
      setSelectedOption({ label: selectedOption, value });
      historyPush(RESOURCES, [`type=${pageEnum[value]}`]);
    }
  };

  return (
    <FormControl
      size="small"
      className={`${classes.selectContainer} ${
        isPluginPage() ? classes.active : undefined
      }`}>
      <Select
        value={selectedOption.value}
        onChange={handleSelect}
        IconComponent={() => (
          <ArrowDropDownIcon
            className={classes.arrowDropdown}
            fontSize={'large'}
          />
        )}
        input={<OutlinedInput className={classes.input} />}
        MenuProps={{ classes: { paper: classes.select } }}>
        <MenuItem key={'Resources'} value={-1} disabled>
          {'Resources'}
        </MenuItem>
        {pluginResourceOptions.map((p, i) => {
          return (
            <MenuItem key={p + i} value={i}>
              {p}
            </MenuItem>
          );
        })}
      </Select>
    </FormControl>
  );
};

const ComposedPluginSelectComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PluginSelectComponent'),
)(PluginSelectComponent);

export default ComposedPluginSelectComponent;
